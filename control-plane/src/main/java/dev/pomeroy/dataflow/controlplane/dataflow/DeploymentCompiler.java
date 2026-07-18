package dev.pomeroy.dataflow.controlplane.dataflow;

import java.util.List;

/**
 * The lifecycle's port to Compilation: deploy-time semantic validation and the
 * artifacts a Deployment freezes — the Execution Plan (as its canonical JSON document)
 * and the Kestra flow YAML carrying the Deployment version label.
 *
 * <p>A port for the same reason as {@link OrchestratorFlows}: the {@code compiler}
 * module consumes this module's Dataflow Config, so calling it directly from here
 * would cycle. The adapter composing {@code compiler} and {@code compiler-kestra}
 * lives in the application root.
 */
public interface DeploymentCompiler {

	CompilationResult compile(String slug, DataflowConfig config, int version);

	sealed interface CompilationResult permits Compiled, Rejected {
	}

	/** The artifacts an accepted config freezes into a Deployment. */
	record Compiled(String planJson, String flowYaml) implements CompilationResult {
	}

	/** The structured semantic violations a rejected config reports (ADR-0005). */
	record Rejected(List<DeployViolation> violations) implements CompilationResult {
	}

	/** Mirrors the compiler's violation shape: a stable rule id plus a message. */
	record DeployViolation(String rule, String message) {
	}
}
