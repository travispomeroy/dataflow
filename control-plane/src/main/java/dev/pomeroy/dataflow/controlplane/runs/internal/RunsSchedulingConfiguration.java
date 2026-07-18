package dev.pomeroy.dataflow.controlplane.runs.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling exists in the application for exactly one reason so far: the runs
 * poller. It is enabled here, next to that reason.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RunsSchedulingConfiguration {
}
