package dev.pomeroy.dataflow.controlplane.compilerhop;

import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;

/**
 * The compiler-hop module's public API: an Execution Plan becomes the Hop engine
 * artifact for the {@code hop × batch} cell — a pipeline producing every file of the
 * feed plus the workflow that runs it and stages the files (see {@link HopArtifact}).
 *
 * <p>The XML is fully deterministic: same plan in, same bytes out — no GUIDs, no
 * wall-clock, stable ordering — which is what makes the committed goldens a valid
 * byte-comparison (M1.5 prior art). Credentials never appear: the artifact carries
 * only {@code minio://} paths, and the MinIO VFS credentials late-bind in the
 * executing layer as {@code HOP_MINIO_*} system properties (ADR-0002 as amended,
 * spike #22).
 *
 * <p>The artifact's runtime contract is three parameters: {@code RUN_ID} (the Kestra
 * execution id, naming the staging folder), {@code BUSINESS_DATE} (resolved into
 * every file name), and {@code OUT_DIR} (the local folder files are produced into
 * before staging, defaulted so only the first two need passing).
 */
public interface HopPipelineCompiler {

	HopArtifact compile(ExecutionPlan plan);
}
