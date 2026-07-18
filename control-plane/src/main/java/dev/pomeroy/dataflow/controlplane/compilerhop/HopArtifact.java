package dev.pomeroy.dataflow.controlplane.compilerhop;

/**
 * The compiled Hop engine artifact: a pipeline ({@code .hpl}) that extracts, merges,
 * transforms and produces every file of the feed into a local folder, and the
 * workflow ({@code .hwf}) that runs it and then stages the produced files to the
 * platform Staging store over MinIO VFS (ADR-0001). The workflow is the entry point
 * — {@code hop-run} it with the pipeline file alongside; both file names derive from
 * the plan's slug so the pair travels as one unit (the runner ships both as task
 * input files, M2.5).
 *
 * <p>The two-file shape is a Hop 2.18.1 defect workaround, not a preference: direct
 * {@code TextFileOutput} → {@code minio://} silently stages truncated objects, while
 * a workflow Copy Files action stages byte-identically (spike #22, flagged on the M2
 * spec #19).
 */
public record HopArtifact(String pipelineFileName, String pipelineXml,
		String workflowFileName, String workflowXml) {
}
