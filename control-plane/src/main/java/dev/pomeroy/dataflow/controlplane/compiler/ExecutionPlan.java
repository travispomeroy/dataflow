package dev.pomeroy.dataflow.controlplane.compiler;

import java.util.List;

/**
 * The fully resolved form of a Dataflow Config: catalog references expanded into
 * physical extraction, merge, transform and file-production steps, plus the staging
 * path convention and delivery spec. Engine-agnostic but physically complete —
 * except secret material, which appears as references only (ADR-0002 as amended).
 *
 * <p>The plan owns its entire vocabulary: no catalog or dataflow-module types appear
 * in its shape, so engine compilers read plans only — the seam that makes engine #3
 * tractable (ADR-0002). {@code schedule} is {@code null} for a manual-only Dataflow.
 */
public record ExecutionPlan(String slug, Engine engine, ExecutionModel executionModel,
		Schedule schedule, Extraction extraction, List<TransformStep> transforms,
		List<OutputFile> files, Staging staging, Delivery delivery) {

	public ExecutionPlan {
		transforms = transforms == null ? List.of() : List.copyOf(transforms);
		files = files == null ? List.of() : List.copyOf(files);
	}
}
