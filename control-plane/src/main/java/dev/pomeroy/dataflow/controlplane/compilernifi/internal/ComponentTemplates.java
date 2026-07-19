package dev.pomeroy.dataflow.controlplane.compilernifi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-component-type templates for the NiFi 2.10.0 shapes the compiler emits: the
 * type, its bundle, every static property at its live default and every static
 * property descriptor. Extracted from the M4.2 spike's live export
 * ({@code spikes/m4.2-nifi-data-plane/artifacts/m42-flow-definition.json}) — NiFi
 * 2.x property names differ from 1.x internals, so templates come from what the
 * pinned image actually serializes, never from docs memory (spike #39). The compiler
 * overlays the property values it owns and adds its dynamic properties.
 */
final class ComponentTemplates {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private ComponentTemplates() {
	}

	/** A fresh mutable copy — callers overlay freely without sharing state. */
	static Map<String, Object> load(String typeSimpleName) {
		try (InputStream in = ComponentTemplates.class
				.getResourceAsStream("templates/" + typeSimpleName + ".json")) {
			if (in == null) {
				throw new IllegalStateException(
						"no committed template for NiFi type '%s'".formatted(typeSimpleName));
			}
			return MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {
			});
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
