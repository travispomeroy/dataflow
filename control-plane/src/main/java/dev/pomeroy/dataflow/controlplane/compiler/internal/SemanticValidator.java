package dev.pomeroy.dataflow.controlplane.compiler.internal;

import dev.pomeroy.dataflow.controlplane.compiler.SemanticViolation;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import dev.pomeroy.dataflow.controlplane.dataflow.DestinationNode;
import dev.pomeroy.dataflow.controlplane.dataflow.Edge;
import dev.pomeroy.dataflow.controlplane.dataflow.Node;
import dev.pomeroy.dataflow.controlplane.dataflow.SourceNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deploy-time semantic rule set (ADR-0005), applied to the config graph as a
 * whole: connected, acyclic, exactly one Delivery, every path running Source → … →
 * Delivery, compilable-now (= linear until M7), operator fields set. All rules run —
 * the violations list is the canvas's complete fix-it list, not the first failure.
 *
 * <p>Assumes a structurally valid document (save-time validation, M1.2): edges whose
 * ends don't resolve to nodes are ignored here rather than crashing the rule set.
 */
class SemanticValidator {

	List<SemanticViolation> validate(DataflowConfig config) {
		List<SemanticViolation> violations = new ArrayList<>();
		Graph graph = new Graph(config);

		long deliveries = config.nodes().stream().filter(DestinationNode.class::isInstance).count();
		if (deliveries != 1) {
			violations.add(new SemanticViolation("delivery-count",
					"a Dataflow delivers to exactly one Destination; found %d".formatted(deliveries)));
		}

		if (!graph.connected()) {
			violations.add(new SemanticViolation("disconnected",
					"the graph must be one connected whole; disconnected parts cannot deploy"));
		}

		if (graph.cyclic()) {
			violations.add(new SemanticViolation("cycle",
					"the graph must be acyclic; data cannot flow in a circle"));
		}

		for (Node node : config.nodes()) {
			if (graph.outDegree(node) > 1 || graph.inDegree(node) > 1) {
				violations.add(new SemanticViolation("non-linear",
						"node '%s' branches or converges; only linear Dataflows compile until M7"
								.formatted(node.id())));
			}
			if (graph.inDegree(node) == 0 && !(node instanceof SourceNode)) {
				violations.add(new SemanticViolation("headless-path",
						"node '%s' starts a path but is not a Source".formatted(node.id())));
			}
			if (graph.outDegree(node) == 0 && !(node instanceof DestinationNode)) {
				violations.add(new SemanticViolation("dangling-path",
						"node '%s' ends a path but is not the Delivery".formatted(node.id())));
			}
			if (node instanceof SourceNode && graph.inDegree(node) > 0) {
				violations.add(new SemanticViolation("source-mid-path",
						"Source '%s' has an incoming edge; Sources start paths".formatted(node.id())));
			}
			if (node instanceof DestinationNode && graph.outDegree(node) > 0) {
				violations.add(new SemanticViolation("delivery-mid-path",
						"Destination '%s' has an outgoing edge; every path terminates at the Delivery"
								.formatted(node.id())));
			}
		}

		if (config.engine() == null || config.executionModel() == null) {
			violations.add(new SemanticViolation("missing-operator-fields",
					"engine and execution model must be set before deploying (operator fields)"));
		}

		return violations;
	}

	/** The config graph with unresolvable edges dropped, plus the shape queries. */
	private static final class Graph {

		private final List<Node> nodes;

		private final Map<String, List<String>> outgoing = new HashMap<>();

		private final Map<String, List<String>> incoming = new HashMap<>();

		Graph(DataflowConfig config) {
			this.nodes = config.nodes();
			for (Node node : nodes) {
				outgoing.put(node.id(), new ArrayList<>());
				incoming.put(node.id(), new ArrayList<>());
			}
			for (Edge edge : config.edges()) {
				if (outgoing.containsKey(edge.from()) && outgoing.containsKey(edge.to())) {
					outgoing.get(edge.from()).add(edge.to());
					incoming.get(edge.to()).add(edge.from());
				}
			}
		}

		int outDegree(Node node) {
			return outgoing.get(node.id()).size();
		}

		int inDegree(Node node) {
			return incoming.get(node.id()).size();
		}

		boolean connected() {
			if (nodes.size() <= 1) {
				return true;
			}
			Set<String> reached = new HashSet<>();
			Deque<String> frontier = new ArrayDeque<>(List.of(nodes.getFirst().id()));
			while (!frontier.isEmpty()) {
				String id = frontier.pop();
				if (reached.add(id)) {
					frontier.addAll(outgoing.get(id));
					frontier.addAll(incoming.get(id));
				}
			}
			return reached.size() == nodes.size();
		}

		boolean cyclic() {
			// Kahn's algorithm: whatever cannot be topologically ordered is on a cycle.
			Map<String, Integer> remainingIn = new HashMap<>();
			incoming.forEach((id, from) -> remainingIn.put(id, from.size()));
			Deque<String> ready = new ArrayDeque<>(
					remainingIn.entrySet().stream()
							.filter(entry -> entry.getValue() == 0)
							.map(Map.Entry::getKey)
							.toList());
			int ordered = 0;
			while (!ready.isEmpty()) {
				String id = ready.pop();
				ordered++;
				for (String next : outgoing.get(id)) {
					if (remainingIn.merge(next, -1, Integer::sum) == 0) {
						ready.push(next);
					}
				}
			}
			return ordered < nodes.size();
		}
	}
}
