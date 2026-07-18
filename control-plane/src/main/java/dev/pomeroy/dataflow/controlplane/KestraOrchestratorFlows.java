package dev.pomeroy.dataflow.controlplane;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.dataflow.OrchestratorFlows;
import org.springframework.stereotype.Component;

/**
 * Application-root glue behind the {@code dataflow} module's {@link OrchestratorFlows}
 * port: the Orchestrator is Kestra, and the flow's identity is the Dataflow's slug.
 * Lives outside every module for the same cycle-breaking reason as
 * {@link CompilerBackedDeploymentCompiler}.
 */
@Component
class KestraOrchestratorFlows implements OrchestratorFlows {

	private final KestraClient kestra;

	KestraOrchestratorFlows(KestraClient kestra) {
		this.kestra = kestra;
	}

	@Override
	public void put(String slug, String flowYaml) {
		kestra.putFlow(slug, flowYaml);
	}

	@Override
	public void remove(String slug) {
		kestra.deleteFlow(slug);
	}
}
