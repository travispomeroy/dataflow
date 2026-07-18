package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import static java.util.stream.Collectors.toUnmodifiableSet;

import dev.pomeroy.dataflow.controlplane.catalog.Catalog;
import dev.pomeroy.dataflow.controlplane.catalog.Client;
import dev.pomeroy.dataflow.controlplane.catalog.Destination;
import dev.pomeroy.dataflow.controlplane.catalog.Source;
import dev.pomeroy.dataflow.controlplane.dataflow.ClientFilterNode;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import dev.pomeroy.dataflow.controlplane.dataflow.DestinationNode;
import dev.pomeroy.dataflow.controlplane.dataflow.Edge;
import dev.pomeroy.dataflow.controlplane.dataflow.Node;
import dev.pomeroy.dataflow.controlplane.dataflow.SourceNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Save-time validation, structural only (ADR-0005): node ids are usable, edges
 * reference existing nodes, refs resolve against the Catalog. Deliberately NOT here:
 * connected, acyclic, exactly-one-Delivery, compilable-now — those are Deploy's
 * semantic rules (M1.4/M1.6), so the canvas can hold any half-built state.
 */
@Component
class StructuralValidator {

	private final Catalog catalog;

	StructuralValidator(Catalog catalog) {
		this.catalog = catalog;
	}

	List<Violation> validate(DataflowConfig config) {
		List<Violation> violations = new ArrayList<>();

		Set<String> nodeIds = new HashSet<>();
		for (Node node : config.nodes()) {
			if (node.id() == null || node.id().isBlank()) {
				violations.add(new Violation("every node needs a non-blank id"));
			}
			else if (!nodeIds.add(node.id())) {
				violations.add(new Violation("duplicate node id '%s'".formatted(node.id())));
			}
		}

		for (Edge edge : config.edges()) {
			for (String end : new String[] { edge.from(), edge.to() }) {
				if (end == null || !nodeIds.contains(end)) {
					violations.add(new Violation(
							"edge %s -> %s references unknown node id '%s'"
									.formatted(edge.from(), edge.to(), end)));
				}
			}
		}

		Set<String> sourceIds = catalog.sources().stream().map(Source::id).collect(toUnmodifiableSet());
		Set<String> destinationIds = catalog.destinations().stream().map(Destination::id)
				.collect(toUnmodifiableSet());
		Set<String> clientIds = catalog.clients().stream().map(Client::id).collect(toUnmodifiableSet());
		for (Node node : config.nodes()) {
			switch (node) {
				case SourceNode source -> {
					if (!sourceIds.contains(source.sourceId())) {
						violations.add(new Violation("node '%s' references unknown Source '%s'"
								.formatted(source.id(), source.sourceId())));
					}
				}
				case DestinationNode destination -> {
					if (!destinationIds.contains(destination.destinationId())) {
						violations.add(new Violation("node '%s' references unknown Destination '%s'"
								.formatted(destination.id(), destination.destinationId())));
					}
				}
				case ClientFilterNode filter -> filter.clientIds().stream()
						.filter(clientId -> !clientIds.contains(clientId))
						.forEach(clientId -> violations.add(
								new Violation("node '%s' references unknown Client '%s'"
										.formatted(filter.id(), clientId))));
			}
		}

		return violations;
	}
}
