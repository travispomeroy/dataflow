package dev.pomeroy.dataflow.controlplane.dataflow;

/**
 * A directed edge of the Dataflow Config DAG: data flows {@code from} one node id
 * {@code to} another. Both ends must reference existing nodes (structural validation).
 */
public record Edge(String from, String to) {
}
