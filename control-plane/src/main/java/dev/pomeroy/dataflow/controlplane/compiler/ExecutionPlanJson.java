package dev.pomeroy.dataflow.controlplane.compiler;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one way an Execution Plan becomes bytes: deterministic serialization —
 * declaration-order properties, two-space indent, LF regardless of platform, trailing
 * newline, no wall-clock anywhere. Deployment snapshots (M1.6) and the committed
 * golden are byte-comparable because everything goes through here.
 */
public final class ExecutionPlanJson {

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.addModule(new ExecutionPlanModule())
			.build();

	private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter(
			Separators.createDefaultInstance()
					.withObjectNameValueSpacing(Separators.Spacing.AFTER)
					.withObjectEmptySeparator("")
					.withArrayEmptySeparator(""))
			.withObjectIndenter(new DefaultIndenter("  ", "\n"))
			.withArrayIndenter(new DefaultIndenter("  ", "\n"));

	private ExecutionPlanJson() {
	}

	public static String write(ExecutionPlan plan) {
		return MAPPER.writer().with(PRINTER).writeValueAsString(plan) + "\n";
	}

	public static ExecutionPlan read(String json) {
		return MAPPER.readValue(json, ExecutionPlan.class);
	}
}
