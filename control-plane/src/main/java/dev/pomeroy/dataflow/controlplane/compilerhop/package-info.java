/**
 * The {@code compiler-hop} module (hyphens are not legal in package names):
 * Execution Plan → Apache Hop engine artifact for the {@code hop × batch} cell — a
 * deterministic pipeline ({@code .hpl}) with generated page-loop extraction for every
 * paginated upstream API, the latest-record collapse, merge, transforms and file
 * production, wrapped in a workflow ({@code .hwf}) that stages the produced files to
 * MinIO over VFS (direct {@code minio://} output is broken in Hop 2.18.1 — spike
 * #22). Engine compilers read plans only, so {@code compiler} is the sole allowed
 * dependency — never {@code catalog} or {@code dataflow} (ADR-0002).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "compiler")
package dev.pomeroy.dataflow.controlplane.compilerhop;
