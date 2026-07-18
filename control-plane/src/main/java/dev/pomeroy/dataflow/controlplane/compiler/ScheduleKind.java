package dev.pomeroy.dataflow.controlplane.compiler;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The kind of plan Schedule; {@code daily} is the only POC kind.
 */
public enum ScheduleKind {

	@JsonProperty("daily")
	DAILY
}
