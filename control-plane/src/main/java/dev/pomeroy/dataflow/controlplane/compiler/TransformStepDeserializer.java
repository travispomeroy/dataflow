package dev.pomeroy.dataflow.controlplane.compiler;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Resolves a transform step's {@code kind} discriminator to its concrete record —
 * the same pattern the dataflow module uses for config nodes.
 */
class TransformStepDeserializer extends ValueDeserializer<TransformStep> {

	@Override
	public TransformStep deserialize(JsonParser parser, DeserializationContext context) {
		JsonNode tree = context.readTree(parser);
		String kind = tree.path("kind").stringValue(null);
		return switch (kind) {
			case "clientFilter" -> context.readTreeAsValue(tree, ClientFilterStep.class);
			case null, default -> context.reportInputMismatch(TransformStep.class,
					"unknown transform step kind '%s' (expected clientFilter)", kind);
		};
	}
}
