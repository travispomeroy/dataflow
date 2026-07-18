package dev.pomeroy.dataflow.controlplane.dataflow;

import java.util.List;

/**
 * The persisted, purely logical representation of a Dataflow: a DAG of nodes and
 * edges (ADR-0005), an optional {@link Schedule} ({@code null} = manual-only), and the
 * operator-set {@link Engine} / {@link ExecutionModel} axes (ADR-0003). Never contains
 * physical details — those live behind the Catalog.
 *
 * <p>Any structurally well-formed document is a saveable Draft, however half-built;
 * semantic rules (connected, acyclic, linear-until-M7) belong to Deploy, not here.
 */
public record DataflowConfig(List<Node> nodes, List<Edge> edges, Schedule schedule,
		Engine engine, ExecutionModel executionModel) {

	public DataflowConfig {
		nodes = nodes == null ? List.of() : List.copyOf(nodes);
		edges = edges == null ? List.of() : List.copyOf(edges);
	}
}
