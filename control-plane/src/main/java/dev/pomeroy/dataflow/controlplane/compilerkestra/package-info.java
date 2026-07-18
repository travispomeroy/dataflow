/**
 * The {@code compiler-kestra} module (hyphens are not legal in package names):
 * Execution Plan → per-Dataflow Kestra flow YAML — schedule trigger, concurrency 1,
 * engine-runner task, staging pull, hidden-upload + rename SFTP delivery (ADR-0001)
 * (M1). Engine compilers read plans only, so {@code compiler} is the sole allowed
 * dependency — never {@code catalog} or {@code dataflow} (ADR-0002).
 *
 * <p>Also home to the {@link dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient}
 * — everything that speaks Kestra lives behind this module's API. The deploy lifecycle
 * (issue #16) and the runs poller (M1.7) reach it from outside; it adds no module
 * dependency of its own.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "compiler")
package dev.pomeroy.dataflow.controlplane.compilerkestra;
