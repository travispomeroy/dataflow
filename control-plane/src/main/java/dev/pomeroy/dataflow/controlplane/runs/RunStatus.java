package dev.pomeroy.dataflow.controlplane.runs;

/**
 * The closed four-state Run status model (spec #10): {@code QUEUED → RUNNING →
 * SUCCEEDED | FAILED}. Kestra's whole state zoo maps in totally — an unmapped state
 * reads as in-flight rather than fabricating an outcome — and the raw state is
 * preserved verbatim in the Run's detail field, so nothing is lost while dashboards
 * reason about four words.
 */
public enum RunStatus {

	QUEUED, RUNNING, SUCCEEDED, FAILED;

	public static RunStatus of(String kestraState) {
		return switch (kestraState) {
			case "CREATED", "QUEUED", "RESTARTED" -> QUEUED;
			case "SUCCESS", "WARNING" -> SUCCEEDED;
			case "FAILED", "KILLED", "CANCELLED", "SKIPPED", "RETRIED" -> FAILED;
			default -> RUNNING;
		};
	}

	public boolean terminal() {
		return this == SUCCEEDED || this == FAILED;
	}
}
