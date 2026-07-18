package dev.pomeroy.dataflow.controlplane.compiler;

/**
 * Where the Engine leaves produced files for the Orchestrator to pick up — the
 * Engine ↔ Delivery handoff (ADR-0001). {@code pathConvention} is resolved except the
 * {@code {runId}} token, which only exists at run time.
 */
public record Staging(String pathConvention) {
}
