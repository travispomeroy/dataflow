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
 * verbatim in {@code detail}; timing is Kestra's own. Delivered files are a raw JSON
 * document — an empty array until M2 records names and record counts and gives it a
 * domain shape.
 */
@Table("run")
record RunEntity(@Id UUID id, UUID dataflowId, String kestraExecutionId, RunStatus status,
		String detail, Instant startedAt, Instant endedAt, String deliveredFiles) {

	/** No files delivered yet — the truthful M1 document for every Run. */
	static final String NO_FILES_YET = "[]";

	/** A newly seen execution — run-now's eager QUEUED record or a poller discovery. */
	static RunEntity of(UUID dataflowId, KestraExecution execution) {
		return new RunEntity(null, dataflowId, execution.id(), RunStatus.of(execution.state()),
				execution.state(), execution.startDate(), execution.endDate(), NO_FILES_YET);
	}

	/** The poller's refresh: Kestra's view of state and timing wins, all else stands. */
	RunEntity updatedFrom(KestraExecution execution) {
		return new RunEntity(id, dataflowId, kestraExecutionId, RunStatus.of(execution.state()),
				execution.state(), execution.startDate(), execution.endDate(), deliveredFiles);
	}

	RunEntity withId(UUID id) {
		return new RunEntity(id, dataflowId, kestraExecutionId, status, detail, startedAt, endedAt,
				deliveredFiles);
	}
}
