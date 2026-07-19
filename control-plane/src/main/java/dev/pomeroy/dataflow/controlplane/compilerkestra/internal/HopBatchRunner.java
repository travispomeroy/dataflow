package dev.pomeroy.dataflow.controlplane.compilerkestra.internal;

import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compilerhop.HopArtifact;
import dev.pomeroy.dataflow.controlplane.compilerhop.HopPipelineCompiler;
import org.springframework.stereotype.Component;

/**
 * The {@code hop × batch} Runner (M2.5): a script task on the Docker task runner
 * launches the pinned Hop image as an ephemeral sibling container on the compose
 * network — per-Run freshness by construction, no registry pull at run time. The
 * compiled artifact pair rides as {@code inputFiles} so the flow pushed to Kestra
 * stays the single self-contained Deployment artifact, and the workflow resolves
 * the pipeline from the same folder.
 *
 * <p>One class per engine matrix cell: the flow compiler dispatches on the plan's
 * {@code (engine, executionModel)} and each real cell owns its task shape in a class
 * like this one (M4 {@code nifi × server}, M5 {@code hop × server} will add theirs).
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
@Component
public class HopBatchRunner {

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

	public HopBatchRunner(HopPipelineCompiler hopCompiler) {
		this.hopCompiler = hopCompiler;
	}

	/** The engine-runner task, YAML lines ready to splice into the flow's task list. */
	String task(ExecutionPlan plan) {
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
}
