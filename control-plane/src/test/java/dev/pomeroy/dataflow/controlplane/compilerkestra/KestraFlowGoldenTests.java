package dev.pomeroy.dataflow.controlplane.compilerkestra;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pomeroy.dataflow.controlplane.compiler.Engine;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionModel;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlanJson;
import dev.pomeroy.dataflow.controlplane.compilerhop.HopArtifact;
import dev.pomeroy.dataflow.controlplane.compilerhop.internal.DeterministicHopXmlCompiler;
import dev.pomeroy.dataflow.controlplane.compilerkestra.internal.DeterministicFlowYamlCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The Kestra compiler's golden seam (issue #15): the canonical Positions Feed
 * Execution Plan — read from its own committed golden, the module's only input —
 * compiles to flow YAML that byte-matches the committed golden. Regenerate with
 * {@code ./mvnw test -Dtest=KestraFlowGoldenTests -Dregenerate.goldens} after a
 * reviewed flow-shape change — regeneration is deterministic, so a rerun must produce
 * a byte-identical file. Surefire runs with the module base directory as working
 * directory, so the repo root is one level up.
 */
class KestraFlowGoldenTests {

	static final Path GOLDEN_PLAN = Path.of("../e2e/golden/positions-feed.plan.json");

	static final Path GOLDEN_FLOW = Path.of("../e2e/golden/positions-feed.flow.yaml");

	static final int VERSION = 1;

	KestraFlowCompiler compiler = new DeterministicFlowYamlCompiler(new DeterministicHopXmlCompiler());

	@Test
	void theCanonicalPlanCompilesToTheCommittedGoldenByteForByte() throws Exception {
		String yaml = compiler.compile(canonicalPlan(), VERSION);

		if (System.getProperty("regenerate.goldens") != null) {
			Files.createDirectories(GOLDEN_FLOW.getParent());
			Files.writeString(GOLDEN_FLOW, yaml);
		}

		assertThat(yaml).isEqualTo(Files.readString(GOLDEN_FLOW));
	}

	@Test
	void compilingTwiceProducesByteIdenticalOutput() throws Exception {
		String first = compiler.compile(canonicalPlan(), VERSION);
		String second = compiler.compile(canonicalPlan(), VERSION);

		assertThat(second).isEqualTo(first);
	}

	@Test
	void theFlowCarriesStableIdentityAndTheDeploymentVersionLabel() throws Exception {
		String yaml = compiler.compile(canonicalPlan(), 7);

		assertThat(yaml).startsWith("id: positions-feed\nnamespace: dataflow\n")
				.contains("dataflow.version: \"7\"");
	}

	@Test
	void theFlowQueuesASecondTriggerBehindTheRunningExecution() throws Exception {
		assertThat(compiler.compile(canonicalPlan(), VERSION))
				.contains("concurrency:\n  behavior: QUEUE\n  limit: 1\n");
	}

	/**
	 * The structured daily Schedule compiles to a cron trigger — cron is a compilation
	 * detail here, never part of a config or plan.
	 */
	@Test
	void theDailyScheduleCompilesToACronTriggerInItsTimezone() throws Exception {
		assertThat(compiler.compile(canonicalPlan(), VERSION))
				.contains("cron: \"30 6 * * *\"")
				.contains("timezone: America/New_York");
	}

	@Test
	void aManualOnlyPlanCompilesWithNoScheduleTrigger() throws Exception {
		assertThat(compiler.compile(manualOnly(), VERSION)).doesNotContain("triggers:")
				.doesNotContain("io.kestra.plugin.core.trigger.Schedule");
	}

	/**
	 * Business Date is real API surface (issue #25): every compiled flow declares the
	 * optional {@code businessDate} input, so a run-now override and a no-input
	 * scheduled trigger both work against the same flow.
	 */
	@Test
	void theFlowDeclaresAnOptionalBusinessDateInput() throws Exception {
		assertThat(compiler.compile(canonicalPlan(), VERSION)).contains("""
				inputs:
				  - id: businessDate
				    type: DATE
				    required: false
				""");
	}

	/**
	 * Absent an override, Business Date is the run date in the Schedule's timezone
	 * (issue #25) — the canonical plan schedules in America/New_York.
	 */
	@Test
	void businessDateDefaultsToTheRunDateInTheSchedulesTimezone() throws Exception {
		assertThat(compiler.compile(canonicalPlan(), VERSION)).contains(
				"BUSINESS_DATE: \"{{ inputs.businessDate ?? (execution.startDate | date('yyyy-MM-dd', timeZone='America/New_York')) }}\"");
	}

	/** A manual-only Dataflow has no Schedule to borrow a timezone from: UTC. */
	@Test
	void aManualOnlyPlanDefaultsBusinessDateInUtc() throws Exception {
		assertThat(compiler.compile(manualOnly(), VERSION)).contains(
				"BUSINESS_DATE: \"{{ inputs.businessDate ?? (execution.startDate | date('yyyy-MM-dd', timeZone='UTC')) }}\"");
	}

	@Test
	void theFlowCarriesTheContractedTasksInDeliveryOrder() throws Exception {
		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml.indexOf("- id: engine_runner"))
				.isLessThan(yaml.indexOf("- id: staging_pull"));
		assertThat(yaml.indexOf("- id: staging_pull"))
				.isLessThan(yaml.indexOf("- id: delivery_hidden_upload"));
		assertThat(yaml.indexOf("- id: delivery_hidden_upload"))
				.isLessThan(yaml.indexOf("- id: delivery_rename"));
		assertThat(yaml.indexOf("- id: delivery_rename"))
				.isLessThan(yaml.indexOf("- id: " + KestraFlowCompiler.COUNT_TASK_ID));
	}

	/**
	 * The Run record's audit point (M2.7, spec #19): only after the atomic rename does
	 * a count task tally data rows (lines minus header) per delivered file, from the
	 * same downloaded objects the delivery loops iterated — and captures the
	 * {@code [{name, records}, …]} array as the task output the runs poller reads.
	 */
	@Test
	void theCountTaskTalliesTheStagedFilesAndCapturesDeliveredFiles() throws Exception {
		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml).contains("""
				  - id: count_records
				    type: io.kestra.plugin.scripts.shell.Commands
				    taskRunner:
				      type: io.kestra.plugin.core.runner.Process
				    inputFiles: "{{ outputs.staging_pull.objects | jq('map({key: .key, value: .uri}) | from_entries') | first | toJson }}"
				""");
		// The downloaded objects land under their staging keys, so the tally walks
		// the same per-Run prefix the staging pull downloaded.
		assertThat(yaml).contains("cd \"positions-feed/{{ execution.id }}/\"")
				.contains("$(wc -l < \"$f\") - 1")
				.contains("\\\"deliveredFiles\\\":");
	}

	/**
	 * ADR-0001: a partial feed is never visible — every file uploads under a hidden
	 * name, and renames to its final name happen only after all uploads completed.
	 */
	@Test
	void deliveryUploadsHiddenAndRenamesOnlyAfterAllUploads() throws Exception {
		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml).contains(".part");
		assertThat(yaml.indexOf("io.kestra.plugin.fs.sftp.Upload"))
				.isLessThan(yaml.indexOf("io.kestra.plugin.fs.sftp.Move"));
	}

	/**
	 * ADR-0002 as amended: the flow names secrets, never their values. The mock
	 * world's credentials are committed in infra/.env, so the test can grep for
	 * them literally.
	 */
	@Test
	void theFlowNamesSecretsButNeverTheirValues() throws Exception {
		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml).contains("{{ secret('SFTP_POMEROY') }}")
				.contains("{{ secret('MINIO_ACCESS_KEY') }}")
				.contains("{{ secret('MINIO_SECRET_KEY') }}");
		for (String key : new String[] { "SFTP_PASSWORD", "MINIO_ROOT_USER",
				"MINIO_ROOT_PASSWORD" }) {
			assertThat(yaml).doesNotContain(envValue(key));
		}
	}

	/**
	 * M2.5: the engine runner is real — the pinned Hop image as an ephemeral sibling
	 * container on the compose network, resolved without a registry pull (spec #19).
	 */
	@Test
	void theEngineRunnerRunsThePinnedHopImageAsAnEphemeralSiblingContainer()
			throws Exception {
		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml).contains("type: io.kestra.plugin.scripts.shell.Commands")
				.contains("type: io.kestra.plugin.scripts.runner.docker.Docker")
				.contains("containerImage: apache/hop:2.18.1")
				.contains("networkMode: dataflow_default")
				.contains("pullPolicy: IF_NOT_PRESENT")
				.doesNotContain("placeholder");
	}

	/**
	 * The flow pushed to Kestra is the single, self-contained Deployment artifact
	 * (spec #19): both compiled Hop files ride as {@code inputFiles}, byte-identical
	 * to what the Hop compiler produces.
	 */
	@Test
	void theFlowEmbedsBothHopArtifactFilesVerbatimAsInputFiles() throws Exception {
		HopArtifact artifact = new DeterministicHopXmlCompiler().compile(canonicalPlan());

		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml).contains("      " + artifact.pipelineFileName() + ": |\n"
				+ blockScalar(artifact.pipelineXml()));
		assertThat(yaml).contains("      " + artifact.workflowFileName() + ": |\n"
				+ blockScalar(artifact.workflowXml()));
	}

	/**
	 * The spike-mandated scaffold (#22): {@code minio://} resolves only when a
	 * {@code MinioConnectionDefinition} named {@code minio} sits in the active
	 * project's metadata folder — the runner ships it as an input file and copies it
	 * into place before {@code hop-run}.
	 */
	@Test
	void theEngineRunnerInstallsTheMinioVfsScaffoldBeforeRunningTheWorkflow()
			throws Exception {
		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml).contains("minio.json: |")
				.contains("\"name\": \"minio\"")
				.contains("${HOP_MINIO_ACCESS_KEY}")
				.contains(
						"cp minio.json /opt/hop/config/projects/default/metadata/MinioConnectionDefinition/minio.json")
				.contains("./hop-run.sh --file=\"$WORKDIR/positions-feed.hwf\" --runconfig=local");
		assertThat(yaml.indexOf("cp minio.json")).isLessThan(yaml.indexOf("./hop-run.sh"));
	}

	/**
	 * The artifact's runtime contract: {@code RUN_ID} is the Kestra execution id
	 * (Run maps 1:1), {@code BUSINESS_DATE} the override-or-run-date resolution
	 * (issue #25), and MinIO credentials late-bind as {@code HOP_MINIO_*} system
	 * properties from Kestra secrets.
	 */
	@Test
	void theEngineRunnerCarriesRunIdentityAndMinioCredentialsInEnv() throws Exception {
		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml).contains("RUN_ID: \"{{ execution.id }}\"")
				.contains("BUSINESS_DATE: \"{{ inputs.businessDate ?? ")
				.contains("-DHOP_MINIO_ACCESS_KEY={{ secret('MINIO_ACCESS_KEY') }}")
				.contains("-DHOP_MINIO_SECRET_KEY={{ secret('MINIO_SECRET_KEY') }}")
				.contains("--parameters=RUN_ID=\"$RUN_ID\",BUSINESS_DATE=\"$BUSINESS_DATE\"");
	}

	/**
	 * Only the {@code hop × batch} cell of the engine matrix is real (M2); every
	 * other cell keeps the honest placeholder until its milestone lands it
	 * (M4 nifi × server, M5 hop × server).
	 */
	@Test
	void aNonHopBatchPlanKeepsThePlaceholderEngineRunner() throws Exception {
		ExecutionPlan hop = canonicalPlan();
		ExecutionPlan nifi = new ExecutionPlan(hop.slug(), Engine.NIFI,
				ExecutionModel.SERVER, hop.schedule(), hop.extraction(), hop.transforms(),
				hop.files(), hop.staging(), hop.delivery());

		String yaml = compiler.compile(nifi, VERSION);

		assertThat(yaml).contains("- id: engine_runner")
				.contains("type: io.kestra.plugin.core.log.Log").contains("placeholder")
				.doesNotContain("io.kestra.plugin.scripts.runner.docker.Docker");
	}

	private ExecutionPlan manualOnly() throws Exception {
		ExecutionPlan scheduled = canonicalPlan();
		return new ExecutionPlan(scheduled.slug(), scheduled.engine(),
				scheduled.executionModel(), null, scheduled.extraction(),
				scheduled.transforms(), scheduled.files(), scheduled.staging(),
				scheduled.delivery());
	}

	/** The YAML block-scalar shape the compiler embeds file content with. */
	private String blockScalar(String content) {
		return content.lines()
				.map(line -> line.isEmpty() ? "" : "        " + line)
				.collect(Collectors.joining("\n", "", "\n"));
	}

	private String envValue(String key) throws Exception {
		return Files.readAllLines(Path.of("../infra/.env")).stream()
				.filter(line -> line.startsWith(key + "="))
				.map(line -> line.substring(key.length() + 1)).findFirst().orElseThrow();
	}

	private ExecutionPlan canonicalPlan() throws Exception {
		return ExecutionPlanJson.read(Files.readString(GOLDEN_PLAN));
	}
}
