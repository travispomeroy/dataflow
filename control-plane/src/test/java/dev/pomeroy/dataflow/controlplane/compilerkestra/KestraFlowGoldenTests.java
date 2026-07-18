package dev.pomeroy.dataflow.controlplane.compilerkestra;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlanJson;
import dev.pomeroy.dataflow.controlplane.compilerkestra.internal.DeterministicFlowYamlCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
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

	KestraFlowCompiler compiler = new DeterministicFlowYamlCompiler();

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
		ExecutionPlan scheduled = canonicalPlan();
		ExecutionPlan manualOnly = new ExecutionPlan(scheduled.slug(), scheduled.engine(),
				scheduled.executionModel(), null, scheduled.extraction(),
				scheduled.transforms(), scheduled.files(), scheduled.staging(),
				scheduled.delivery());

		assertThat(compiler.compile(manualOnly, VERSION)).doesNotContain("triggers:")
				.doesNotContain("io.kestra.plugin.core.trigger.Schedule");
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
	 * world's one delivery secret is committed in infra/.env, so the test can grep for
	 * it literally.
	 */
	@Test
	void theFlowNamesTheDeliverySecretButNeverItsValue() throws Exception {
		String sftpPassword = Files.readAllLines(Path.of("../infra/.env")).stream()
				.filter(line -> line.startsWith("SFTP_PASSWORD="))
				.map(line -> line.substring("SFTP_PASSWORD=".length()))
				.findFirst().orElseThrow();

		String yaml = compiler.compile(canonicalPlan(), VERSION);

		assertThat(yaml).contains("{{ secret('SFTP_POMEROY') }}")
				.doesNotContain(sftpPassword);
	}

	private ExecutionPlan canonicalPlan() throws Exception {
		return ExecutionPlanJson.read(Files.readString(GOLDEN_PLAN));
	}
}
