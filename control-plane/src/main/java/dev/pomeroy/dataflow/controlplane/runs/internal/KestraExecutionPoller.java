package dev.pomeroy.dataflow.controlplane.runs.internal;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import dev.pomeroy.dataflow.controlplane.dataflow.Dataflows;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The namespace-wide poller (spec #10): every 5 seconds, every execution in the one
 * dataflow namespace flows through the same idempotent upsert — discovering runs the
 * control plane never triggered (Kestra starts scheduled ones on its own) and
 * refreshing the ones it did. An execution whose flow matches no Dataflow slug is
 * skipped: Kestra history can outlive a hard-deleted Dataflow. M9 swaps this loop for
 * an event consumer behind the same runs module API.
 *
 * <p>Off by property for {@code @SpringBootTest} contexts only — the M1 gate
 * walkthrough exercises the loop against live Kestra, which is the poller's one
 * intended test seam.
 */
@Component
@ConditionalOnProperty(name = "runs.poller.enabled", havingValue = "true", matchIfMissing = true)
class KestraExecutionPoller {

	private static final Logger log = LoggerFactory.getLogger(KestraExecutionPoller.class);

	private final KestraClient kestra;

	private final Dataflows dataflows;

	private final RunRecorder recorder;

	KestraExecutionPoller(KestraClient kestra, Dataflows dataflows, RunRecorder recorder) {
		this.kestra = kestra;
		this.dataflows = dataflows;
		this.recorder = recorder;
	}

	@Scheduled(fixedDelay = 5000)
	void poll() {
		List<KestraExecution> executions;
		try {
			executions = kestra.listExecutions();
		}
		catch (RuntimeException e) {
			// A wedged Kestra is a known compose failure mode; the next tick retries.
			log.warn("Skipping runs poll — Kestra executions API unavailable: {}", e.getMessage());
			return;
		}
		for (KestraExecution execution : executions) {
			dataflows.findBySlug(execution.flowId())
					.ifPresent(dataflow -> recorder.upsert(dataflow, execution));
		}
	}
}
