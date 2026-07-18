package dev.pomeroy.dataflow.controlplane.dataflow;

import java.util.Optional;
import java.util.UUID;

/**
 * The dataflow module's read seam for run tracking (issue #17): just enough identity
 * for the runs module to admit run-now (is the Dataflow deployed?) and to attribute
 * Kestra executions back to their Dataflow — stable flow identity makes the flow ID
 * the slug. M9's event consumer replaces the poller behind this same seam.
 */
public interface Dataflows {

	Optional<DataflowRef> find(UUID id);

	/** Kestra flow IDs are Dataflow slugs — the poller's way home. */
	Optional<DataflowRef> findBySlug(String slug);

	record DataflowRef(UUID id, String slug, boolean deployed) {
	}
}
