package dev.pomeroy.dataflow.controlplane.runs.internal;

import java.util.Comparator;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * What a Run shipped (M2.7, ADR-0001's audit point): one entry per delivered file —
 * final name, data-row count — sorted by name by construction, so the record reads
 * the same no matter which order the Orchestrator reported the files in. Empty is the
 * truthful document for a Run that delivered nothing: every Run starts there, and a
 * FAILED Run never leaves.
 *
 * <p>The document shape — the bare {@code [{name, records}, …]} array — is known only
 * here: the jsonb column and the Orchestrator's task output both materialize through
 * these factories.
 */
record DeliveredFiles(List<DeliveredFile> files) {

	private static final TypeReference<List<DeliveredFile>> DOCUMENT = new TypeReference<>() {
	};

	DeliveredFiles {
		files = files.stream().sorted(Comparator.comparing(DeliveredFile::name)).toList();
	}

	static DeliveredFiles none() {
		return new DeliveredFiles(List.of());
	}

	/** The stored jsonb document, read back. */
	static DeliveredFiles fromJson(ObjectMapper mapper, String json) {
		return new DeliveredFiles(mapper.readValue(json, DOCUMENT));
	}

	/** The count task's already-parsed output value, as captured from Kestra. */
	static DeliveredFiles fromDocument(ObjectMapper mapper, Object document) {
		return new DeliveredFiles(mapper.convertValue(document, DOCUMENT));
	}

	record DeliveredFile(String name, long records) {
	}
}
