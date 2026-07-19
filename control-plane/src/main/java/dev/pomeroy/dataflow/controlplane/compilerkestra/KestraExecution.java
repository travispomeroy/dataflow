package dev.pomeroy.dataflow.controlplane.compilerkestra;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One Kestra execution as the control plane sees it (issue #17): identity, the flow it
 * belongs to (the flow ID is the Dataflow's slug — stable flow identity), the raw
 * Kestra state verbatim, and Kestra's own timing. The runs module maps the state into
 * the closed four-state Run model; this record takes no view on it.
 *
 * <p>{@code businessDate} is the execution's explicit {@code businessDate} input when
 * the trigger carried one (issue #29), {@code null} otherwise — a scheduled or
 * override-less run leaves the compiled flow's run-date default to resolve it, and the
 * runs module mirrors that rule when it records the Run.
 */
public record KestraExecution(String id, String flowId, String state, Instant startDate,
		Instant endDate, LocalDate businessDate) {
}
