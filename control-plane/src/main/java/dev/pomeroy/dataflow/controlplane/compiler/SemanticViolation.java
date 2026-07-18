package dev.pomeroy.dataflow.controlplane.compiler;

/**
 * One entry of the structured violations list produced by deploy-time semantic
 * validation — the machine-readable shape the 422 problem detail (M1.6) and the M3
 * canvas consume. {@code rule} is a stable identifier, one per failure mode:
 *
 * <ul>
 * <li>{@code delivery-count} — not exactly one Destination node</li>
 * <li>{@code disconnected} — the graph is not one connected component</li>
 * <li>{@code cycle} — the graph is not acyclic</li>
 * <li>{@code non-linear} — a node fans out or converges (compilable-now = linear;
 * M7 lifts this)</li>
 * <li>{@code headless-path} — a path starts at a node that is not a Source</li>
 * <li>{@code dangling-path} — a path ends at a node that is not the Delivery</li>
 * <li>{@code source-mid-path} — a Source has an incoming edge</li>
 * <li>{@code delivery-mid-path} — the Destination has an outgoing edge</li>
 * <li>{@code missing-operator-fields} — engine or execution model unset</li>
 * </ul>
 */
public record SemanticViolation(String rule, String message) {
}
