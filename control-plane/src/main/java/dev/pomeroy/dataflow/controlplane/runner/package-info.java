/**
 * Runners: the four Engine × Execution Model strategies (NiFi/Hop × server/batch) that
 * invoke an Engine for a Run (ADR-0003) (M2–M5). Only deploy-time engine work lives
 * here (the NiFi REST client behind
 * {@link dev.pomeroy.dataflow.controlplane.runner.NiFiDeployments}); each cell's
 * run-time task shape lives with the flow compiler in {@code compilerkestra}, because
 * runs execute with the control plane stopped. {@code compilernifi} is allowed for the
 * artifact's parameter contract — the context created at deploy must carry exactly the
 * names the compiled artifact references.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "compilernifi")
package dev.pomeroy.dataflow.controlplane.runner;
