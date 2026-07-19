package dev.pomeroy.dataflow.controlplane.compilernifi.internal;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one way a flow definition becomes bytes: deterministic serialization — keys
 * sorted alphabetically at every level, two-space indent, LF regardless of platform,
 * trailing newline, no wall-clock anywhere (the {@code ExecutionPlanJson}
 * discipline). Array order is meaning, not presentation, so it stays as constructed.
 */
final class FlowDefinitionJson {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter(
			Separators.createDefaultInstance()
					.withObjectNameValueSpacing(Separators.Spacing.AFTER)
					.withObjectEmptySeparator("")
					.withArrayEmptySeparator(""))
			.withObjectIndenter(new DefaultIndenter("  ", "\n"))
			.withArrayIndenter(new DefaultIndenter("  ", "\n"));

	private FlowDefinitionJson() {
	}

	static String write(Map<String, Object> document) {
		return MAPPER.writer().with(PRINTER).writeValueAsString(sorted(document)) + "\n";
	}

	private static Object sorted(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> out = new TreeMap<>();
			map.forEach((key, entry) -> out.put((String) key, sorted(entry)));
			return out;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(FlowDefinitionJson::sorted).toList();
		}
		return value;
	}
}
