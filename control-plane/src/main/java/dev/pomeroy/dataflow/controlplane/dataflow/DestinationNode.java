package dev.pomeroy.dataflow.controlplane.dataflow;

/**
 * A node delivering to a Catalog Destination. {@code destinationId} references the
 * Catalog; it must resolve at save time (structural validation).
 */
public record DestinationNode(String id, String type, String destinationId) implements Node {

	public DestinationNode {
		if (!"destination".equals(type)) {
			throw new IllegalArgumentException("a destination node's type is 'destination', not '%s'"
					.formatted(type));
		}
	}
}
