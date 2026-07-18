package dev.pomeroy.dataflow.controlplane.compiler;

import tools.jackson.databind.module.SimpleModule;

/**
 * The Jackson module every mapper reading Execution Plans needs: it resolves the
 * {@code kind} discrimination of transform steps. Registered for the
 * {@link TransformStep} interface only — the concrete records (de)serialize as plain
 * records, which is what keeps the plan document byte-faithful.
 */
public class ExecutionPlanModule extends SimpleModule {

	public ExecutionPlanModule() {
		super("execution-plan");
		addDeserializer(TransformStep.class, new TransformStepDeserializer());
	}
}
