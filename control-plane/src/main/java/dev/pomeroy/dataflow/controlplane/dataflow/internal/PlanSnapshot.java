package dev.pomeroy.dataflow.controlplane.dataflow.internal;

/**
 * The frozen Execution Plan exactly as the compiler serialized it. Deliberately an
 * opaque document here: the plan's type belongs to the {@code compiler} module, and
 * the lifecycle only stores and exposes it — byte-faithful, never reinterpreted.
 */
record PlanSnapshot(String json) {
}
