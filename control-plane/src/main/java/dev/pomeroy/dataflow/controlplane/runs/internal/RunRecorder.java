package dev.pomeroy.dataflow.controlplane.runs.internal;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The one idempotent upsert path (spec #10), keyed by Kestra execution ID: run-now's
 * eager first record, the poller's discovery of scheduled runs, and the poller's
 * refresh of known ones all land here. Kestra's view of state and timing always wins —
 * there is no merge logic to disagree with it.
 */
@Service
class RunRecorder {

	private final RunRepository runs;

	RunRecorder(RunRepository runs) {
		this.runs = runs;
	}

	RunEntity upsert(UUID dataflowId, KestraExecution execution) {
		return runs.findByKestraExecutionId(execution.id())
				.map(known -> refresh(known, execution))
				.orElseGet(() -> discover(dataflowId, execution));
	}

	/** Saves only when Kestra reports something new — a settled Run stops writing. */
	private RunEntity refresh(RunEntity known, KestraExecution execution) {
		RunEntity updated = known.updatedFrom(execution);
		return updated.equals(known) ? known : runs.save(updated);
	}

	private RunEntity discover(UUID dataflowId, KestraExecution execution) {
		try {
			return runs.save(RunEntity.of(dataflowId, execution));
		}
		catch (DuplicateKeyException e) {
			// The unique execution-id constraint is the authority when run-now and a
			// poller tick race to first-record the same execution — the loser refreshes
			// the row the winner wrote.
			return refresh(runs.findByKestraExecutionId(execution.id()).orElseThrow(), execution);
		}
	}
}
