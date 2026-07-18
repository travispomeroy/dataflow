package dev.pomeroy.dataflow.controlplane.compilerkestra.internal;

import dev.pomeroy.dataflow.controlplane.compiler.Delivery;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.Schedule;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraFlowCompiler;
import java.time.LocalTime;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Writes the flow YAML by hand — no YAML library, so every byte is owned here and the
 * committed golden stays a valid comparison. Values that are Kestra Pebble
 * expressions are always double-quoted, as are the cron expression and the SFTP port
 * (a string-typed property of the fs plugin); everything else the compiler emits is a
 * plain scalar by construction.
 *
 * <p>Platform conventions live here, not in the plan: the Staging store (the compose
 * MinIO, bucket {@code staging}, credentials as Kestra secrets), the hidden-upload
 * name shape ({@code .name.part} → {@code name}, ADR-0001), and the cron translation
 * of the structured daily Schedule.
 */
@Component
public class DeterministicFlowYamlCompiler implements KestraFlowCompiler {

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
				""".formatted(plan.slug(), NAMESPACE, deploymentVersion));
		yaml.append(tasks(plan));
		if (plan.schedule() != null) {
			yaml.append(trigger(plan.schedule()));
		}
		return yaml.toString();
	}

	private String tasks(ExecutionPlan plan) {
		return "tasks:\n" + engineRunner(plan) + stagingPull(plan) + delivery(plan.delivery());
	}

	/**
	 * M1's engine task is a placeholder that stages nothing, so the staging pull that
	 * follows fails — the honest failure the M1 gate asserts. M2 replaces this one
	 * task with a real engine runner and the same flow goes green.
	 */
	private String engineRunner(ExecutionPlan plan) {
		return """
				  - id: engine_runner
				    type: io.kestra.plugin.core.log.Log
				    message: Engine runner placeholder (engine %s, execution model %s) — stages nothing, so the staging pull fails until M2 lands a real engine task
				""".formatted(lower(plan.engine()), lower(plan.executionModel()));
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
				""".formatted(plan.staging().pathConvention().replace("{runId}",
				"{{ execution.id }}"));
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
