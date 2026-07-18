package dev.pomeroy.dataflow.controlplane.compilerkestra;

import java.time.Instant;

/**
 * One Kestra execution as the control plane sees it (issue #17): identity, the flow it
 * belongs to (the flow ID is the Dataflow's slug — stable flow identity), the raw
 * Kestra state verbatim, and Kestra's own timing. The runs module maps the state into
 * the closed four-state Run model; this record takes no view on it.
 */
public record KestraExecution(String id, String flowId, String state, Instant startDate,
		Instant endDate) {
}
