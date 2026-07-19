package dev.pomeroy.dataflow.controlplane.compilerkestra;

import java.util.List;
import java.util.Map;

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

	/**
	 * Starts an execution of the flow right now — run-now (issue #17). {@code inputs}
	 * carries the flow's execution inputs (issue #25: the optional
	 * {@code businessDate} override); empty means the flow's own defaults resolve.
	 * Returns the freshly created execution (raw state {@code CREATED}), so the
	 * caller can record it synchronously.
	 */
	KestraExecution createExecution(String flowId, Map<String, String> inputs);

	/**
	 * Every execution in the one dataflow namespace, paged through exhaustively — the
	 * runs poller's single discovery path for triggered and scheduled runs alike.
	 */
	List<KestraExecution> listExecutions();

	/**
	 * The output variables the given task captured during the execution — a script
	 * task's {@code ::{"outputs": …}::} markers (M2.7: the count task's delivered
	 * files). Empty when the execution or task run does not exist or captured nothing;
	 * a wedged Kestra still surfaces as a runtime exception.
	 */
	Map<String, Object> taskOutputVars(String executionId, String taskId);
}
