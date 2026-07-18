package dev.pomeroy.dataflow.controlplane.dataflow;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Resolves the two-level node discrimination — {@code type}, then {@code kind} for
 * transforms — that Jackson's own polymorphism cannot chain, and turns an unknown
 * discriminator into the exact message the save-time violations array carries.
 */
class NodeDeserializer extends ValueDeserializer<Node> {

	@Override
	public Node deserialize(JsonParser parser, DeserializationContext context) {
		JsonNode tree = context.readTree(parser);
		String type = tree.path("type").stringValue(null);
		return switch (type) {
			case "source" -> context.readTreeAsValue(tree, SourceNode.class);
			case "destination" -> context.readTreeAsValue(tree, DestinationNode.class);
			case "transform" -> transform(tree, context);
			case null, default -> context.reportInputMismatch(Node.class,
					"unknown node type '%s' (expected source, transform or destination)", type);
		};
	}

	private TransformNode transform(JsonNode tree, DeserializationContext context) {
		String kind = tree.path("kind").stringValue(null);
		return switch (kind) {
			case "clientFilter" -> context.readTreeAsValue(tree, ClientFilterNode.class);
			case null, default -> context.reportInputMismatch(TransformNode.class,
					"unknown transform kind '%s' (expected clientFilter)", kind);
		};
	}
}
