package dev.pomeroy.dataflow.controlplane.compiler;

/**
 * When the plan's Dataflow runs automatically, in the plan's own vocabulary. Still
 * structured, never a cron string — cron is a Kestra-compiler detail (M1.5).
 * {@code time} is a 24h {@code HH:mm} wall-clock time in the named {@code timezone}.
 */
public record Schedule(ScheduleKind kind, String time, String timezone) {
}
