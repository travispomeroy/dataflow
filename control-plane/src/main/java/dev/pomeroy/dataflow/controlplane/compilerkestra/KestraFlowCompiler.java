package dev.pomeroy.dataflow.controlplane.compilerkestra;

import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;

/**
 * The compiler-kestra module's public API: an Execution Plan becomes the one
 * per-Dataflow Kestra flow YAML. Stable flow identity — namespace {@link #NAMESPACE},
 * flow ID = the plan's slug, the Deployment version as a label — so deploy (M1.6) is a
 * single overwrite and every Kestra execution is attributable to its Deployment.
 *
 * <p>The YAML is fully deterministic: same plan and version in, same bytes out — no
 * wall-clock, stable ordering — which is what makes the committed golden a valid
 * byte-comparison (M1.5). Secrets appear as {@code {{ secret('…') }}} references only
 * (ADR-0002 as amended).
 */
public interface KestraFlowCompiler {

	/**
	 * Every Dataflow flow lives in this one Kestra namespace: Kestra contains exactly
	 * the actively deployed Dataflows and nothing else, so the runs poller (M1.7) can
	 * watch the namespace wholesale.
	 */
	String NAMESPACE = "dataflow";

	String compile(ExecutionPlan plan, int deploymentVersion);
}
