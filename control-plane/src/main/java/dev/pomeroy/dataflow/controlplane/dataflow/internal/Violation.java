package dev.pomeroy.dataflow.controlplane.dataflow.internal;

/**
 * One entry of the structured violations array carried by a 422 problem detail — the
 * machine-readable shape the M3 canvas consumes. Deploy-time semantic validation
 * (M1.4/M1.6) reuses it.
 */
record Violation(String message) {
}
