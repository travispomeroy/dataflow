package dev.pomeroy.dataflow.controlplane;

import dev.pomeroy.dataflow.controlplane.dataflow.EngineDeployments;
import dev.pomeroy.dataflow.controlplane.runner.NiFiDeployments;
import org.springframework.stereotype.Component;

/**
 * Application-root glue behind the {@code dataflow} module's {@link EngineDeployments}
 * port: the only Engine with server-side deploy state is NiFi (M4.4), so the port is
 * the {@code runner} module's NiFi client. Lives outside every module for the same
 * cycle-breaking reason as {@link KestraOrchestratorFlows}.
 */
@Component
class NiFiEngineDeployments implements EngineDeployments {

	private final NiFiDeployments nifi;

	NiFiEngineDeployments(NiFiDeployments nifi) {
		this.nifi = nifi;
	}

	@Override
	public void put(String slug, int version, String flowDefinitionJson) {
		nifi.put(slug, version, flowDefinitionJson);
	}
}
