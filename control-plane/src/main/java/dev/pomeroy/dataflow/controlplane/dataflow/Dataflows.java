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

	/**
	 * {@code businessDateTimezone} is the timezone the compiled flow's run-date default
	 * resolves Business Date in (issue #25): the active Deployment's frozen Schedule
	 * timezone, UTC when that Deployment is manual-only — and UTC when undeployed, where
	 * only stale Orchestrator history can still surface executions.
	 */
	record DataflowRef(UUID id, String slug, boolean deployed, String businessDateTimezone) {
	}
}
