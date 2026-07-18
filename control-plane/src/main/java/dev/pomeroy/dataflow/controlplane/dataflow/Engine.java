package dev.pomeroy.dataflow.controlplane.dataflow;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The pluggable ETL technology performing this Dataflow's extract/transform work.
 * Operator-set, orthogonal to {@link ExecutionModel} (ADR-0003); invisible to end
 * users.
 */
public enum Engine {

	@JsonProperty("hop")
	HOP,

	@JsonProperty("nifi")
	NIFI
}
