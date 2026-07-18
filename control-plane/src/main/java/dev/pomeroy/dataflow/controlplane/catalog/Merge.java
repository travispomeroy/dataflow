package dev.pomeroy.dataflow.controlplane.catalog;

/**
 * The hidden composition inside a Source: all upstream APIs share {@code key} as the
 * internal id their records merge on. Per-API {@link UpstreamApi#joinOn()} lists the
 * exact join columns (the orders↔positions overlap adds {@code symbol}).
 */
public record Merge(String key) {
}
