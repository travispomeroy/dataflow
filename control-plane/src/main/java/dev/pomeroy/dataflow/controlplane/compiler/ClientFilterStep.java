package dev.pomeroy.dataflow.controlplane.compiler;

import java.util.List;

/**
 * The resolved "filter by Clients" step: keep only rows whose {@code field} value is
 * one of {@code clientIds}. {@code field} is the logical column the merge key projects
 * onto (upstream "investor" vocabulary already renamed to Client vocabulary) — the
 * resolution engines need that the logical config never carries.
 */
public record ClientFilterStep(String kind, String field, List<String> clientIds)
		implements TransformStep {

	public ClientFilterStep {
		if (!"clientFilter".equals(kind)) {
			throw new IllegalArgumentException(
					"a client filter step's kind is 'clientFilter', not '%s'".formatted(kind));
		}
		clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
	}
}
