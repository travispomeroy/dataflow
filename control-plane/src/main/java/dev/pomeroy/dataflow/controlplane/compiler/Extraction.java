package dev.pomeroy.dataflow.controlplane.compiler;

import java.util.List;

/**
 * The physical extraction step: the Source's hidden composition expanded — every
 * upstream API with resolved pagination, plus how their records merge into one
 * logical dataset. What the user picked by name, made executable.
 */
public record Extraction(String baseUrl, Merge merge, List<ApiExtraction> apis) {

	public Extraction {
		apis = apis == null ? List.of() : List.copyOf(apis);
	}
}
