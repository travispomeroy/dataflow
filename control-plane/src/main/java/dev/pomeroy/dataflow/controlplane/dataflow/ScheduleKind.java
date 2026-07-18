package dev.pomeroy.dataflow.controlplane.dataflow;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The kind of Schedule. {@code DAILY} ("at a time in a timezone, every day") is the
 * only POC kind; business-day and holiday calendars are productionization notes.
 */
public enum ScheduleKind {

	@JsonProperty("daily")
	DAILY
}
