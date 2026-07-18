package dev.pomeroy.dataflow.controlplane.compiler;

/**
 * The delivery spec: the resolved Destination the Orchestrator ships files to
 * (ADR-0001). {@code credentialsRef} names a secret in the executing layer's secret
 * store — the plan never carries secret values (ADR-0002 as amended).
 */
public record Delivery(String host, int port, String username, String basePath,
		String credentialsRef) {
}
