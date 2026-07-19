package dev.pomeroy.dataflow.controlplane.compilernifi;

import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;

/**
 * The compiler-nifi module's public API: an Execution Plan becomes the NiFi engine
 * artifact for the {@code nifi × server} cell — a single self-contained
 * flow-definition snapshot (see {@link NiFiArtifact}).
 *
 * <p>The JSON is fully deterministic: identifiers are UUIDv5 minted from each
 * component's logical path, canvas positions fixed, keys sorted, no wall-clock —
 * same plan in, same bytes out, which is what makes the committed goldens a valid
 * byte-comparison (M1/M2 prior art) and lets two compilations of the same plan diff
 * meaningfully. {@code ExecuteScript} never appears: hand-written script blocks would
 * void the generated-artifact legibility comparison ADR-0003 exists to enable.
 */
public interface NiFiFlowCompiler {

	NiFiArtifact compile(ExecutionPlan plan);
}
