package dev.pomeroy.dataflow.controlplane.compiler;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How the Engine executes a Run — {@code server} or {@code batch} (ADR-0003) — in the
 * plan's own vocabulary (deliberately duplicated from the dataflow module — engine
 * compilers read plans only).
 */
public enum ExecutionModel {

	@JsonProperty("server")
	SERVER,

	@JsonProperty("batch")
	BATCH
}
