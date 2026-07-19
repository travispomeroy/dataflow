package dev.pomeroy.dataflow.controlplane.runs.internal;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import dev.pomeroy.dataflow.controlplane.runs.RunStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The run row of the relational spine: one Run per Kestra execution, keyed by the
 * execution ID. Status is the closed four-state model with the raw Kestra state
 * verbatim in {@code detail}; timing is Kestra's own. Delivered files are a jsonb
 * document with the M2.7 domain shape — empty until the Run's terminal SUCCEEDED
 * captures what was shipped, empty forever when it fails.
 */
@Table("run")
record RunEntity(@Id UUID id, UUID dataflowId, String kestraExecutionId, RunStatus status,
		String detail, Instant startedAt, Instant endedAt, DeliveredFiles deliveredFiles) {

	/** A newly seen execution — run-now's eager QUEUED record or a poller discovery. */
	static RunEntity of(UUID dataflowId, KestraExecution execution) {
		return new RunEntity(null, dataflowId, execution.id(), RunStatus.of(execution.state()),
				execution.state(), execution.startDate(), execution.endDate(),
				DeliveredFiles.none());
	}

	/** The poller's refresh: Kestra's view of state and timing wins, all else stands. */
	RunEntity updatedFrom(KestraExecution execution) {
		return new RunEntity(id, dataflowId, kestraExecutionId, RunStatus.of(execution.state()),
				execution.state(), execution.startDate(), execution.endDate(), deliveredFiles);
	}

	RunEntity withDeliveredFiles(DeliveredFiles deliveredFiles) {
		return new RunEntity(id, dataflowId, kestraExecutionId, status, detail, startedAt,
				endedAt, deliveredFiles);
	}

	RunEntity withId(UUID id) {
		return new RunEntity(id, dataflowId, kestraExecutionId, status, detail, startedAt, endedAt,
				deliveredFiles);
	}
}
