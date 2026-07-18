package dev.pomeroy.dataflow.controlplane.catalog;

/**
 * The connection details a Destination owns. {@code credentialsRef} names a secret in
 * the executing layer's secret store (Kestra) — secret values never appear in the
 * catalog, in Execution Plans, or in compiled artifacts (ADR-0002 as amended).
 */
public record DestinationPhysical(String host, int port, String username, String basePath,
		String credentialsRef) {
}
