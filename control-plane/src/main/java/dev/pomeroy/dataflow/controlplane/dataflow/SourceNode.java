package dev.pomeroy.dataflow.controlplane.dataflow;

/**
 * A node reading a Catalog Source. {@code sourceId} references the Catalog; it must
 * resolve at save time (structural validation).
 */
public record SourceNode(String id, String type, String sourceId) implements Node {

	public SourceNode {
		if (!"source".equals(type)) {
			throw new IllegalArgumentException("a source node's type is 'source', not '%s'"
					.formatted(type));
		}
	}
}
