package dev.pomeroy.dataflow.controlplane.compiler;

import java.util.List;

/**
 * The latest-record collapse rule of a merging API (ADR-0006): of the records sharing
 * one {@link ApiExtraction#joinOn()} key, only the latest survives the merge —
 * {@code latestBy} names the deciding upstream fields, max of the first, later ones
 * breaking ties. Engine compilers implement it as a deterministic latest-per-key
 * reduction before the merge.
 */
public record Collapse(List<String> latestBy) {

	public Collapse {
		latestBy = latestBy == null ? List.of() : List.copyOf(latestBy);
	}
}
