package dev.pomeroy.dataflow.controlplane.compilerkestra;

/**
 * The Kestra API client (issue #16): flow registration in the one {@code dataflow}
 * namespace, speaking the basic-auth dance the M0 compose world established. Built
 * for the deploy lifecycle and reused by the runs poller (M1.7), which extends it
 * with the executions API.
 *
 * <p>Failures surface as runtime exceptions for callers to translate — this module
 * takes no view on HTTP error shapes beyond Kestra's own.
 */
public interface KestraClient {

	/**
	 * Creates or overwrites the flow — stable identity makes deploy a single
	 * overwrite, no cleanup dance.
	 */
	void putFlow(String flowId, String flowYaml);

	/**
	 * Removes the flow entirely; a flow already absent is treated as removed, so the
	 * goal state ("Kestra holds exactly the actively deployed Dataflows") is what the
	 * call asserts.
	 */
	void deleteFlow(String flowId);
}
