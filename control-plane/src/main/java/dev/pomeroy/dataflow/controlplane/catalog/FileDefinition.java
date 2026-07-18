package dev.pomeroy.dataflow.controlplane.catalog;

import java.util.List;

/**
 * The specification of one output file a Dataflow produces from a Source: a token-based
 * name pattern, split by a field so one definition can yield one file per value (e.g.
 * one file per Asset Class), and the {@code columns} projection — the Source's logical
 * fields the file carries, in delivered order.
 */
public record FileDefinition(String id, String name, String sourceId, String namePattern,
		String splitBy, List<String> columns) {
}
