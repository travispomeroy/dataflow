package dev.pomeroy.dataflow.controlplane.dataflow;

/**
 * When the Dataflow runs automatically — a first-class concept, never a bare cron
 * string (cron is a Kestra-compiler detail). {@code time} is a 24h {@code HH:mm}
 * wall-clock time in the named {@code timezone}. A Dataflow with no Schedule is
 * manual-only.
 */
public record Schedule(ScheduleKind kind, String time, String timezone) {
}
