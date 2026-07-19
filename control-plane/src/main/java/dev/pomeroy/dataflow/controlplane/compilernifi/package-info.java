/**
 * The {@code compiler-nifi} module (hyphens are not legal in package names):
 * Execution Plan → NiFi flow-definition snapshot for the {@code nifi × server} cell —
 * generated sequential page-loop extraction for every paginated upstream API (the
 * deliberately cyclic subgraph), the latest-record collapse, chained record merges,
 * transforms, the structural per-Asset-Class split and the staging write (M4).
 * Engine compilers read plans only, so {@code compiler} is the sole allowed
 * dependency — never {@code catalog} or {@code dataflow} (ADR-0002).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "compiler")
package dev.pomeroy.dataflow.controlplane.compilernifi;
