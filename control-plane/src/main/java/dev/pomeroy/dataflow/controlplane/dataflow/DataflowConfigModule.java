package dev.pomeroy.dataflow.controlplane.dataflow;

import tools.jackson.databind.module.SimpleModule;

/**
 * The Jackson module every mapper reading Dataflow Configs needs: it resolves the
 * two-level node discrimination ({@code type}, then {@code kind}). Registered for the
 * {@link Node} interface only — the concrete records (de)serialize as plain records,
 * which is what keeps the canonical examples byte-faithful.
 */
public class DataflowConfigModule extends SimpleModule {

	public DataflowConfigModule() {
		super("dataflow-config");
		addDeserializer(Node.class, new NodeDeserializer());
	}
}
