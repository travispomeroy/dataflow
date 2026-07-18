package dev.pomeroy.dataflow.controlplane.dataflow;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How the Engine executes a Run: {@code server} (long-running engine, runs triggered
 * against it) or {@code batch} (ephemeral run-to-completion). Operator-set, orthogonal
 * to {@link Engine} (ADR-0003).
 */
public enum ExecutionModel {

	@JsonProperty("server")
	SERVER,

	@JsonProperty("batch")
	BATCH
}
