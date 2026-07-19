package dev.pomeroy.dataflow.controlplane.runs.internal;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import dev.pomeroy.dataflow.controlplane.runs.RunStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The run row of the relational spine: one Run per Kestra execution, keyed by the
 * execution ID. Status is the closed four-state model with the raw Kestra state
 * verbatim in {@code detail}; timing is Kestra's own. Business Date is resolved once
 * at recording (issue #29) and refreshed only by an explicit execution input — see
 * {@link RunRecorder}. Delivered files are a jsonb document with the M2.7 domain
 * shape — empty until the Run's terminal SUCCEEDED captures what was shipped, empty
 * forever when it fails.
 */
@Table("run")
record RunEntity(@Id UUID id, UUID dataflowId, String kestraExecutionId, RunStatus status,
		String detail, Instant startedAt, Instant endedAt, LocalDate businessDate,
		DeliveredFiles deliveredFiles) {

	/** A newly seen execution — run-now's eager QUEUED record or a poller discovery. */
	static RunEntity of(UUID dataflowId, KestraExecution execution, LocalDate businessDate) {
		return new RunEntity(null, dataflowId, execution.id(), RunStatus.of(execution.state()),
				execution.state(), execution.startDate(), execution.endDate(), businessDate,
				DeliveredFiles.none());
	}

	/**
	 * The poller's refresh: Kestra's view of state and timing wins, all else stands —
	 * except an explicit Business Date input, which always beats what was resolved at
	 * discovery (covers the poller first-recording a run-now execution it saw without
	 * inputs).
	 */
	RunEntity updatedFrom(KestraExecution execution) {
		return new RunEntity(id, dataflowId, kestraExecutionId, RunStatus.of(execution.state()),
				execution.state(), execution.startDate(), execution.endDate(),
				execution.businessDate() != null ? execution.businessDate() : businessDate,
				deliveredFiles);
	}

	RunEntity withDeliveredFiles(DeliveredFiles deliveredFiles) {
		return new RunEntity(id, dataflowId, kestraExecutionId, status, detail, startedAt,
				endedAt, businessDate, deliveredFiles);
	}

	RunEntity withId(UUID id) {
		return new RunEntity(id, dataflowId, kestraExecutionId, status, detail, startedAt, endedAt,
				businessDate, deliveredFiles);
	}
}
