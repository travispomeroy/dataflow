package dev.pomeroy.dataflow.controlplane.compiler;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The Engine a plan targets, in the plan's own vocabulary (deliberately duplicated
 * from the dataflow module — engine compilers read plans only). The plan stays
 * engine-agnostic in shape; carrying the operator's choice is what lets runners
 * (M2–M5) pick a strategy from the frozen snapshot alone.
 */
public enum Engine {

	@JsonProperty("hop")
	HOP,

	@JsonProperty("nifi")
	NIFI
}
