package dev.pomeroy.dataflow.controlplane.catalog;

import java.util.List;
import java.util.Map;

/**
 * One upstream REST API a Source extracts from. {@code joinOn} is empty for the base
 * API and lists the join columns for the others; {@code fields} projects upstream field
 * names onto the Source's logical fields (where upstream "investor" vocabulary becomes
 * Client vocabulary); {@code collapse} is the latest-record rule for a one-to-many
 * merge, {@code null} when every join key matches at most one record.
 */
public record UpstreamApi(String name, String path, List<String> joinOn, Pagination pagination,
		Map<String, String> fields, Collapse collapse) {
}
