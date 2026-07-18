package dev.pomeroy.dataflow.controlplane.dataflow;

import java.util.List;

/**
 * The "filter by Clients" Transform: only rows belonging to the chosen Clients pass.
 * Client ids reference the clients reference data; they must resolve at save time.
 */
public record ClientFilterNode(String id, String type, String kind, List<String> clientIds)
		implements TransformNode {

	public ClientFilterNode {
		if (!"transform".equals(type) || !"clientFilter".equals(kind)) {
			throw new IllegalArgumentException(
					"a client filter node's type is 'transform' and its kind 'clientFilter', not '%s'/'%s'"
							.formatted(type, kind));
		}
		clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
	}
}
