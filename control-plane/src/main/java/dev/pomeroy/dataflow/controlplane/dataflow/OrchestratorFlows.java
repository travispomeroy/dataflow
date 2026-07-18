package dev.pomeroy.dataflow.controlplane.dataflow;

/**
 * The lifecycle's port to the Orchestrator: Deploy publishes the one per-Dataflow flow
 * (a plain overwrite thanks to stable flow identity), Undeploy removes it so the
 * Orchestrator contains exactly the actively deployed Dataflows and nothing else.
 *
 * <p>A port, not a client: the {@code compiler} module already depends on this module
 * (it consumes Dataflow Configs), so the lifecycle cannot reach Kestra-side modules
 * directly without a cycle — the Kestra-backed implementation is wired in from the
 * application root.
 */
public interface OrchestratorFlows {

	/** Creates or overwrites the Dataflow's flow — deploy's final act. */
	void put(String slug, String flowYaml);

	/** Removes the Dataflow's flow entirely — undeploy. */
	void remove(String slug);
}
