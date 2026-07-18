package dev.pomeroy.dataflow.controlplane.compiler;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import java.util.List;

/**
 * The compiler module's public API: deploy-time semantic validation and Compilation —
 * resolving a Dataflow Config against the Catalog into an engine-agnostic
 * {@link ExecutionPlan} (ADR-0002). Deploy (M1.6) calls {@link #validate} first and
 * turns violations into a 422 problem detail; {@link #compile} refuses an invalid
 * config outright so no plan can ever exist for one.
 */
public interface DataflowCompiler {

	/**
	 * The deploy-time semantic rule set (ADR-0005): connected, acyclic, exactly one
	 * Delivery, every path starting at a Source and terminating at the Delivery,
	 * compilable-now (= linear until M7), and operator fields set. An empty list means
	 * the config is deployable.
	 */
	List<SemanticViolation> validate(DataflowConfig config);

	/**
	 * Resolves the config's Catalog references into an Execution Plan — physically
	 * complete except secret material (ADR-0002 as amended). The slug is the Dataflow's
	 * immutable identity, minted by the dataflow module; the plan carries it so
	 * downstream compilers and the frozen snapshot are self-describing.
	 * @throws IllegalArgumentException if the config has semantic violations
	 */
	ExecutionPlan compile(String slug, DataflowConfig config);
}
