package dev.pomeroy.dataflow.controlplane.runs.internal;

import java.util.Comparator;
import java.util.List;

/**
 * What a Run shipped (M2.7, ADR-0001's audit point): one entry per delivered file —
 * final name, data-row count — sorted by name by construction, so the record reads
 * the same no matter which order the Orchestrator reported the files in. Empty is the
 * truthful document for a Run that delivered nothing: every Run starts there, and a
 * FAILED Run never leaves.
 */
record DeliveredFiles(List<DeliveredFile> files) {

	DeliveredFiles {
		files = files.stream().sorted(Comparator.comparing(DeliveredFile::name)).toList();
	}

	static DeliveredFiles none() {
		return new DeliveredFiles(List.of());
	}

	record DeliveredFile(String name, long records) {
	}
}
