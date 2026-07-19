package dev.pomeroy.dataflow.controlplane.compilerkestra.internal;

import dev.pomeroy.dataflow.controlplane.compiler.Delivery;
import dev.pomeroy.dataflow.controlplane.compiler.Engine;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionModel;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.Schedule;
import dev.pomeroy.dataflow.controlplane.compilerhop.HopArtifact;
import dev.pomeroy.dataflow.controlplane.compilerhop.HopPipelineCompiler;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraFlowCompiler;
import java.time.LocalTime;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Writes the flow YAML by hand — no YAML library, so every byte is owned here and the
 * committed golden stays a valid comparison. Values that are Kestra Pebble
 * expressions are always double-quoted, as are the cron expression and the SFTP port
 * (a string-typed property of the fs plugin); everything else the compiler emits is a
 * plain scalar or a literal block scalar by construction.
 *
 * <p>Platform conventions live here, not in the plan: the Staging store (the compose
 * MinIO, bucket {@code staging}, credentials as Kestra secrets), the hidden-upload
 * name shape ({@code .name.part} → {@code name}, ADR-0001), the cron translation
 * of the structured daily Schedule, and the engine-runner shape per engine matrix
 * cell — for {@code hop × batch} the pinned Hop image as an ephemeral sibling
 * container carrying the embedded engine artifact (M2.5).
 */
@Component
public class DeterministicFlowYamlCompiler implements KestraFlowCompiler {

	/** The pinned engine image (docs/versions.md) — bump only via that registry. */
	static final String HOP_IMAGE = "apache/hop:2.18.1";

	/**
	 * The compose network the sibling engine container joins: the compose project
	 * name is pinned ({@code name: dataflow} in infra/docker-compose.yml), so the
	 * default network name is deterministic.
	 */
	static final String COMPOSE_NETWORK = "dataflow_default";

	/**
	 * Where the shipped {@code MinioConnectionDefinition} must land inside the Hop
	 * container: the image's active project is {@code default}, and only an object in
	 * the project's metadata folder registers the {@code minio://} VFS scheme (#22).
	 */
	static final String HOP_MINIO_METADATA_FOLDER =
			"/opt/hop/config/projects/default/metadata/MinioConnectionDefinition";

	/**
	 * The MinIO connection metadata shipped to the engine container (spike #22
	 * reference JSON): the object's {@code name} registers the scheme; credential
	 * fields are {@code ${HOP_MINIO_*}} expressions late-bound from JVM system
	 * properties, so the artifact carries no secret material (ADR-0002).
	 */
	static final String MINIO_CONNECTION_DEFINITION = """
			{
			  "accessKey": "${HOP_MINIO_ACCESS_KEY}",
			  "cacheTtlSeconds": "5",
			  "description": "Platform Staging store (ADR-0001); credentials late-bound from HOP_MINIO_* system properties (ADR-0002)",
			  "endPointHostname": "${HOP_MINIO_ENDPOINT_HOSTNAME}",
			  "endPointPort": "${HOP_MINIO_ENDPOINT_PORT}",
			  "endPointSecure": false,
			  "name": "minio",
			  "partSize": "5242880",
			  "region": "us-east-1",
			  "secretKey": "${HOP_MINIO_SECRET_KEY}"
			}
			""";

	private final HopPipelineCompiler hopCompiler;

	public DeterministicFlowYamlCompiler(HopPipelineCompiler hopCompiler) {
		this.hopCompiler = hopCompiler;
	}

	@Override
	public String compile(ExecutionPlan plan, int deploymentVersion) {
		StringBuilder yaml = new StringBuilder();
		yaml.append("""
				id: %s
				namespace: %s
				labels:
				  dataflow.version: "%d"
				concurrency:
				  behavior: QUEUE
				  limit: 1
				inputs:
				  - id: businessDate
				    type: DATE
				    required: false
				""".formatted(plan.slug(), NAMESPACE, deploymentVersion));
		yaml.append(tasks(plan));
		if (plan.schedule() != null) {
			yaml.append(trigger(plan.schedule()));
		}
		return yaml.toString();
	}

	private String tasks(ExecutionPlan plan) {
		return "tasks:\n" + engineRunner(plan) + stagingPull(plan) + delivery(plan.delivery())
				+ countRecords(plan);
	}

	/**
	 * One engine-runner task per engine matrix cell. {@code hop × batch} is real
	 * since M2.5; every other cell keeps the honest placeholder — it stages nothing,
	 * so the staging pull that follows fails — until its milestone lands the runner
	 * (M4 {@code nifi × server}, M5 {@code hop × server}).
	 */
	private String engineRunner(ExecutionPlan plan) {
		if (plan.engine() == Engine.HOP && plan.executionModel() == ExecutionModel.BATCH) {
			return hopBatchRunner(plan);
		}
		return """
				  - id: engine_runner
				    type: io.kestra.plugin.core.log.Log
				    message: Engine runner placeholder (engine %s, execution model %s) — stages nothing, so the staging pull fails until this engine matrix cell lands its real runner
				""".formatted(lower(plan.engine()), lower(plan.executionModel()));
	}

	/**
	 * The {@code hop × batch} Runner (M2.5): a script task on the Docker task runner
	 * launches the pinned Hop image as an ephemeral sibling container on the compose
	 * network — per-Run freshness by construction, no registry pull at run time. The
	 * compiled artifact pair rides as {@code inputFiles} so the flow pushed to Kestra
	 * stays the single self-contained Deployment artifact, and the workflow resolves
	 * the pipeline from the same folder.
	 *
	 * <p>The MinIO VFS scaffold is the spike's finding (#22): the {@code minio://}
	 * scheme registers only through a {@code MinioConnectionDefinition} object named
	 * {@code minio} in the active project's metadata folder ({@code --metadata-export}
	 * silently resolves such paths as local files), so the runner copies the shipped
	 * definition into place before {@code hop-run}. Its credential fields are
	 * {@code ${HOP_MINIO_*}} expressions resolved from JVM system properties, injected
	 * via {@code HOP_OPTIONS} from Kestra secrets — the flow names secrets, never
	 * values (ADR-0002). {@code -XX:+AggressiveHeap} is the image's stock
	 * {@code HOP_OPTIONS} value, preserved because setting the variable replaces it.
	 *
	 * <p>{@code BUSINESS_DATE} is the flow's optional {@code businessDate} input when
	 * the trigger provided one, else the run date in the Schedule's timezone — UTC for
	 * a manual-only Dataflow, which has no Schedule to borrow a timezone from (#25).
	 * "Run date" is {@code execution.startDate}, the execution's creation time: a Run
	 * queued behind another (concurrency QUEUE) keeps the date it was triggered on
	 * even if it starts executing after midnight — the as-of date the trigger meant.
	 */
	private String hopBatchRunner(ExecutionPlan plan) {
		HopArtifact artifact = hopCompiler.compile(plan);
		return """
				  - id: engine_runner
				    type: io.kestra.plugin.scripts.shell.Commands
				    taskRunner:
				      type: io.kestra.plugin.scripts.runner.docker.Docker
				      networkMode: %s
				      pullPolicy: IF_NOT_PRESENT
				    containerImage: %s
				    env:
				      RUN_ID: "{{ execution.id }}"
				      BUSINESS_DATE: "{{ inputs.businessDate ?? (execution.startDate | date('yyyy-MM-dd', timeZone='%s')) }}"
				      HOP_OPTIONS: "-XX:+AggressiveHeap -DHOP_MINIO_ENDPOINT_HOSTNAME=minio -DHOP_MINIO_ENDPOINT_PORT=9000 -DHOP_MINIO_ACCESS_KEY={{ secret('MINIO_ACCESS_KEY') }} -DHOP_MINIO_SECRET_KEY={{ secret('MINIO_SECRET_KEY') }}"
				    inputFiles:
				""".formatted(COMPOSE_NETWORK, HOP_IMAGE, businessDateTimezone(plan))
				+ inputFile(artifact.pipelineFileName(), artifact.pipelineXml())
				+ inputFile(artifact.workflowFileName(), artifact.workflowXml())
				+ inputFile("minio.json", MINIO_CONNECTION_DEFINITION)
				+ """
				    commands:
				      - mkdir -p %1$s
				      - cp minio.json %1$s/minio.json
				      - WORKDIR="$PWD" && cd /opt/hop && ./hop-run.sh --file="$WORKDIR/%2$s" --runconfig=local --level=Basic --parameters=RUN_ID="$RUN_ID",BUSINESS_DATE="$BUSINESS_DATE"
				""".formatted(HOP_MINIO_METADATA_FOLDER, artifact.workflowFileName());
	}

	/** The timezone the run-date default resolves Business Date in (#25). */
	private String businessDateTimezone(ExecutionPlan plan) {
		return plan.schedule() != null ? plan.schedule().timezone() : "UTC";
	}

	/**
	 * One embedded input file as a literal block scalar — content lines indented
	 * under the key, empty lines left empty so no line ever carries trailing spaces.
	 */
	private String inputFile(String name, String content) {
		StringBuilder yaml = new StringBuilder("      ").append(name).append(": |\n");
		content.lines().forEach(
				line -> yaml.append(line.isEmpty() ? "" : "        " + line).append('\n'));
		return yaml.toString();
	}

	/**
	 * Pulls the Run's staged files from the platform Staging store (ADR-0001). The
	 * plan's {@code {runId}} token is Kestra's execution id — Run maps 1:1 to Kestra
	 * execution. The MinIO secret names are platform constants the deploy environment
	 * provides alongside the delivery secrets.
	 */
	private String stagingPull(ExecutionPlan plan) {
		return """
				  - id: staging_pull
				    type: io.kestra.plugin.minio.Downloads
				    endpoint: http://minio:9000
				    accessKeyId: "{{ secret('MINIO_ACCESS_KEY') }}"
				    secretKeyId: "{{ secret('MINIO_SECRET_KEY') }}"
				    bucket: staging
				    prefix: "%s"
				    action: NONE
				""".formatted(stagingPrefix(plan));
	}

	/** The per-Run staging prefix, with the plan's {@code {runId}} token late-bound. */
	private String stagingPrefix(ExecutionPlan plan) {
		return plan.staging().pathConvention().replace("{runId}", "{{ execution.id }}");
	}

	/**
	 * Hidden-upload then rename (ADR-0001): every staged file uploads as
	 * {@code .name.part}, and only after the upload loop completes does the rename
	 * loop move files to their final names — a partial feed is never visible. File
	 * names exist only at run time (the {@code splitBy} fan-out), so both loops
	 * iterate over the staging pull's output objects.
	 */
	private String delivery(Delivery delivery) {
		String fileName = "{{ fromJson(taskrun.value).key | substringAfterLast('/') }}";
		return """
				  - id: delivery_hidden_upload
				    type: io.kestra.plugin.core.flow.ForEach
				    values: "{{ outputs.staging_pull.objects }}"
				    tasks:
				      - id: upload_hidden
				        type: io.kestra.plugin.fs.sftp.Upload
				        host: %1$s
				        port: "%2$d"
				        username: %3$s
				        password: "{{ secret('%4$s') }}"
				        from: "{{ fromJson(taskrun.value).uri }}"
				        to: "/%5$s/.%6$s.part"
				  - id: delivery_rename
				    type: io.kestra.plugin.core.flow.ForEach
				    values: "{{ outputs.staging_pull.objects }}"
				    tasks:
				      - id: rename_visible
				        type: io.kestra.plugin.fs.sftp.Move
				        host: %1$s
				        port: "%2$d"
				        username: %3$s
				        password: "{{ secret('%4$s') }}"
				        from: "/%5$s/.%6$s.part"
				        to: "/%5$s/%6$s"
				""".formatted(delivery.host(), delivery.port(), delivery.username(),
				delivery.credentialsRef(), delivery.basePath(), fileName);
	}

	/**
	 * The Run record's audit point (M2.7): what did we ship? Only after the atomic
	 * rename — never before, so the record cannot claim delivery that did not happen —
	 * a count task tallies data rows (lines minus header; the CSV contract guarantees
	 * a header row and trailing newline, so {@code wc -l} minus one is exact) per
	 * delivered file, from the same downloaded objects the delivery loops iterated.
	 * Kestra lays those objects out under their staging keys, so the tally walks the
	 * per-Run prefix. The {@code [{name, records}, …]} array is captured as the
	 * {@code deliveredFiles} output variable the runs poller reads on terminal SUCCESS
	 * — in glob order, on which the run record never depends: the runs module sorts by
	 * name on capture. The Process runner keeps the tally on the Orchestrator itself:
	 * no container, no registry, nothing that can fail after the feed is already
	 * delivered.
	 *
	 * <p>{@code inputFiles} wants a name→uri map, not the objects array the staging
	 * pull outputs (Kestra 1.3.28 rejects the array outright), so the jq expression
	 * rebuilds the map from each object's key and internal-storage uri — keys keep
	 * their full staging paths, which is what lays the files out under the per-Run
	 * prefix the tally walks.
	 */
	private String countRecords(ExecutionPlan plan) {
		return """
				  - id: %s
				    type: io.kestra.plugin.scripts.shell.Commands
				    taskRunner:
				      type: io.kestra.plugin.core.runner.Process
				    inputFiles: "{{ outputs.staging_pull.objects | jq('map({key: .key, value: .uri}) | from_entries') | first | toJson }}"
				    commands:
				      - cd "%s" && files="" && for f in *; do [ -f "$f" ] || continue; files="${files:+$files,}{\\"name\\":\\"$f\\",\\"records\\":$(( $(wc -l < "$f") - 1 ))}"; done && printf '::%%s::\\n' "{\\"outputs\\":{\\"%s\\":[$files]}}"
				""".formatted(KestraFlowCompiler.COUNT_TASK_ID, stagingPrefix(plan),
				KestraFlowCompiler.DELIVERED_FILES_VAR);
	}

	/**
	 * The structured Schedule becomes cron only here — cron is a Kestra compilation
	 * detail, never config or plan vocabulary. The switch is exhaustive so a new
	 * Schedule kind refuses to compile until this translation learns it.
	 */
	private String trigger(Schedule schedule) {
		return switch (schedule.kind()) {
			case DAILY -> {
				LocalTime time = LocalTime.parse(schedule.time());
				yield """
						triggers:
						  - id: schedule
						    type: io.kestra.plugin.core.trigger.Schedule
						    cron: "%d %d * * *"
						    timezone: %s
						""".formatted(time.getMinute(), time.getHour(), schedule.timezone());
			}
		};
	}

	private String lower(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}
}
