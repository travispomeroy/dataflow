package dev.pomeroy.dataflow.controlplane.runs.internal;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraFlowCompiler;
import dev.pomeroy.dataflow.controlplane.dataflow.Dataflows.DataflowRef;
import dev.pomeroy.dataflow.controlplane.runs.RunStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The one idempotent upsert path (spec #10), keyed by Kestra execution ID: run-now's
 * eager first record, the poller's discovery of scheduled runs, and the poller's
 * refresh of known ones all land here. Kestra's view of state and timing always wins —
 * there is no merge logic to disagree with it.
 *
 * <p>Business Date is resolved at discovery (issue #29) by the same rule the compiled
 * flow itself uses (#25): the execution's explicit {@code businessDate} input when the
 * trigger carried one, else the run date — the execution's start — in the Dataflow's
 * business-date timezone. The record keeps that resolution; only an explicit input
 * seen later can replace it.
 *
 * <p>Crossing into terminal SUCCEEDED additionally captures what was shipped (M2.7):
 * the count task's {@code deliveredFiles} output, read back from the execution and
 * stored sorted by name. The capture is part of the transition — if the read-back
 * fails, the record is left untouched and the next poll retries the whole step, so a
 * SUCCEEDED Run never settles without its delivered files. FAILED captures nothing:
 * the empty list is the truthful record of a Run that delivered nothing.
 */
@Service
class RunRecorder {

	private final RunRepository runs;

	private final KestraClient kestra;

	private final ObjectMapper mapper;

	RunRecorder(RunRepository runs, KestraClient kestra, ObjectMapper mapper) {
		this.runs = runs;
		this.kestra = kestra;
		this.mapper = mapper;
	}

	RunEntity upsert(DataflowRef dataflow, KestraExecution execution) {
		return runs.findByKestraExecutionId(execution.id())
				.map(known -> refresh(known, execution))
				.orElseGet(() -> discover(dataflow, execution));
	}

	/** Saves only when Kestra reports something new — a settled Run stops writing. */
	private RunEntity refresh(RunEntity known, KestraExecution execution) {
		RunEntity updated = known.updatedFrom(execution);
		if (updated.status() == RunStatus.SUCCEEDED && known.status() != RunStatus.SUCCEEDED) {
			updated = updated.withDeliveredFiles(capturedDeliveredFiles(execution));
		}
		return updated.equals(known) ? known : runs.save(updated);
	}

	private RunEntity discover(DataflowRef dataflow, KestraExecution execution) {
		RunEntity fresh = RunEntity.of(dataflow.id(), execution, businessDate(dataflow, execution));
		if (fresh.status() == RunStatus.SUCCEEDED) {
			// Already terminal when first seen — a scheduled run that finished
			// before discovery still gets its delivered files.
			fresh = fresh.withDeliveredFiles(capturedDeliveredFiles(execution));
		}
		try {
			return runs.save(fresh);
		}
		catch (DuplicateKeyException e) {
			// The unique execution-id constraint is the authority when run-now and a
			// poller tick race to first-record the same execution — the loser refreshes
			// the row the winner wrote.
			return refresh(runs.findByKestraExecutionId(execution.id()).orElseThrow(), execution);
		}
	}

	/**
	 * The flow's own resolution rule (#25), mirrored: explicit input, else the run date
	 * in the Dataflow's business-date timezone. "Run date" is the execution's start —
	 * its creation time — so a Run queued past midnight keeps the date it was
	 * triggered on.
	 */
	private LocalDate businessDate(DataflowRef dataflow, KestraExecution execution) {
		return execution.businessDate() != null ? execution.businessDate()
				: LocalDate.ofInstant(execution.startDate(),
						ZoneId.of(dataflow.businessDateTimezone()));
	}

	private DeliveredFiles capturedDeliveredFiles(KestraExecution execution) {
		Object captured = kestra.taskOutputVars(execution.id(), KestraFlowCompiler.COUNT_TASK_ID)
				.get(KestraFlowCompiler.DELIVERED_FILES_VAR);
		if (captured == null) {
			return DeliveredFiles.none();
		}
		return DeliveredFiles.fromDocument(mapper, captured);
	}
}
