package dev.pomeroy.dataflow.controlplane.dataflow;

/**
 * The lifecycle's port to the Engine's server-side deploy surface (M4.4): a Deployment
 * whose Engine holds state of its own — NiFi's process group and parameter context —
 * publishes that state at deploy time, so scheduled and manual runs never touch the
 * control plane (the M1/M2 posture). Deploy creates or wholly replaces the engine-side
 * artifacts; redeploys converge the Engine to exactly the active Deployment.
 *
 * <p>A port, not a client, for the same cycle-breaking reason as
 * {@link OrchestratorFlows}: the NiFi-backed implementation composes the {@code runner}
 * module from the application root. Engines without server-side state (Hop batch) never
 * reach this port — the compiled result carries no engine artifact for them.
 */
public interface EngineDeployments {

	/**
	 * Creates or wholly replaces the Dataflow's engine-side artifacts from the
	 * Deployment's compiled flow definition — deploy's engine-side half, before the
	 * Orchestrator learns the new flow.
	 */
	void put(String slug, int version, String flowDefinitionJson);
}
