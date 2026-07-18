package dev.pomeroy.dataflow.controlplane.catalog;

/**
 * The specification of one output file a Dataflow produces from a Source: a token-based
 * name pattern, split by a field so one definition can yield one file per value (e.g.
 * one file per Asset Class).
 */
public record FileDefinition(String id, String name, String sourceId, String namePattern, String splitBy) {
}
