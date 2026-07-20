package dev.pomeroy.dataflow.controlplane;

import dev.pomeroy.dataflow.controlplane.compiler.DataflowCompiler;
import dev.pomeroy.dataflow.controlplane.compiler.Engine;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionModel;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlanJson;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraFlowCompiler;
import dev.pomeroy.dataflow.controlplane.compilernifi.NiFiFlowCompiler;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import dev.pomeroy.dataflow.controlplane.dataflow.DeploymentCompiler;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Application-root glue behind the {@code dataflow} module's {@link DeploymentCompiler}
 * port: semantic validation and Compilation via the {@code compiler} module, then the
 * flow YAML via {@code compiler-kestra} — and, for the {@code nifi × server} cell, the
 * engine-side flow definition via {@code compiler-nifi} (M4.4). Lives outside every
 * module because {@code compiler} depends on {@code dataflow} — the lifecycle calling
 * the compilers directly would cycle the module graph.
 */
@Component
class CompilerBackedDeploymentCompiler implements DeploymentCompiler {

	private final DataflowCompiler compiler;

	private final KestraFlowCompiler kestraCompiler;

	private final NiFiFlowCompiler nifiCompiler;

	CompilerBackedDeploymentCompiler(DataflowCompiler compiler,
			KestraFlowCompiler kestraCompiler, NiFiFlowCompiler nifiCompiler) {
		this.compiler = compiler;
		this.kestraCompiler = kestraCompiler;
		this.nifiCompiler = nifiCompiler;
	}

	@Override
	public CompilationResult compile(String slug, DataflowConfig config, int version) {
		List<DeployViolation> violations = compiler.validate(config).stream()
				.map(violation -> new DeployViolation(violation.rule(), violation.message()))
				.toList();
		if (!violations.isEmpty()) {
			return new Rejected(violations);
		}
		ExecutionPlan plan = compiler.compile(slug, config);
		return new Compiled(ExecutionPlanJson.write(plan),
				kestraCompiler.compile(plan, version), engineFlowDefinition(plan));
	}

	/**
	 * Only the {@code nifi × server} cell deploys engine-side state; every other cell
	 * ships everything inside the flow YAML and publishes nothing to an Engine.
	 */
	private String engineFlowDefinition(ExecutionPlan plan) {
		if (plan.engine() == Engine.NIFI && plan.executionModel() == ExecutionModel.SERVER) {
			return nifiCompiler.compile(plan).flowDefinitionJson();
		}
		return null;
	}
}
