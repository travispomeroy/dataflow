package dev.pomeroy.dataflow.controlplane.compilernifi;

import java.util.List;

/**
 * The compiled NiFi engine artifact: one flow-definition snapshot (the
 * registry-style versioned-flow JSON, the "Download flow definition" shape) that does
 * the entire feed — paginated extraction loops, the latest-record collapse, the
 * chained merges, transforms, per-Asset-Class file production and the staging write —
 * uploaded into NiFi in a single REST call (spec #37; upload mechanics spike #38).
 *
 * <p>NiFi re-mints every live component id on import but preserves each component's
 * compiler-minted {@code identifier} verbatim as {@code versionedComponentId} (spike
 * #38), so the ids below address live components only through that mapping.
 * {@code seedProcessorIds} are the per-API seed {@code GenerateFlowFile}s — minted
 * {@code DISABLED} so a process-group start skips them; the run driver enables each
 * and issues RUN_ONCE, making one-run's-worth-of-trigger explicit.
 * {@code failureConnectionIds} are the connections into the dedicated failure funnel
 * — a run has failed exactly when any of their queue depths is nonzero.
 *
 * <p>The artifact's runtime contract is four parameter names (ADR-0002 — names only,
 * values late-bound through the Dataflow's parameter context at run time):
 * {@code businessDate} and {@code runId} per run, {@code minioAccessKey} and
 * {@code minioSecretKey} for the staging write (sensitive; NiFi drops sensitive
 * values from flow definitions anyway, so carrying references is also physically
 * forced — spike #39).
 */
public record NiFiArtifact(String fileName, String flowDefinitionJson,
		List<String> seedProcessorIds, List<String> failureConnectionIds) {

	public NiFiArtifact {
		seedProcessorIds = seedProcessorIds == null ? List.of() : List.copyOf(seedProcessorIds);
		failureConnectionIds = failureConnectionIds == null ? List.of()
				: List.copyOf(failureConnectionIds);
	}
}
