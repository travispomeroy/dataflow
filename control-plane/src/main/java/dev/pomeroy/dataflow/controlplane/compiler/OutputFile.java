package dev.pomeroy.dataflow.controlplane.compiler;

import java.util.List;

/**
 * One resolved file-production step: the Catalog File Definition the plan will
 * produce. {@code namePattern} keeps its tokens — {@code {businessDate}} and the
 * {@code splitBy} field's values (one file per value) resolve at run time.
 * {@code splitValues} is the split field's closed value set (Catalog knowledge), so an
 * engine compiler can make the one-file-per-value rule structural — a valueless class
 * still yields its header-only file. {@code columns} is the projection: the logical
 * fields the file carries, in delivered order — also the CSV header row verbatim.
 */
public record OutputFile(String fileDefinitionId, String namePattern, String splitBy,
		List<String> splitValues, List<String> columns) {

	public OutputFile {
		splitValues = splitValues == null ? List.of() : List.copyOf(splitValues);
		columns = columns == null ? List.of() : List.copyOf(columns);
	}
}
