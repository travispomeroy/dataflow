/**
 * The {@code compiler-kestra} module (hyphens are not legal in package names):
 * Execution Plan → per-Dataflow Kestra flow YAML — schedule trigger, concurrency 1,
 * engine-runner task, staging pull, hidden-upload + rename SFTP delivery (ADR-0001)
 * (M1). Engine compilers read plans only — never {@code catalog} or {@code dataflow}
 * (ADR-0002) — so {@code compiler} is allowed; {@code compilerhop} joins it in M2.5
 * because the orchestrator flow embeds the compiled engine artifact as the runner
 * task's input files, keeping the flow pushed to Kestra the single self-contained
 * Deployment artifact (spec #19). {@code compilernifi} joins in M4.4 for the same
 * reason's run-time half: the nifi runner task embeds the generated driver, which
 * carries the artifact's deterministic seed and failure-connection ids.
 *
 * <p>Also home to the {@link dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient}
 * — everything that speaks Kestra lives behind this module's API. The deploy lifecycle
 * (issue #16) and the runs poller (M1.7) reach it from outside; it adds no module
 * dependency of its own.
 */
@org.springframework.modulith.ApplicationModule(
		allowedDependencies = { "compiler", "compilerhop", "compilernifi" })
package dev.pomeroy.dataflow.controlplane.compilerkestra;
