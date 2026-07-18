package dev.pomeroy.dataflow.controlplane.compiler;

/**
 * The merge step hidden inside the extraction: all upstream APIs share {@code key} as
 * the internal id their records merge on; per-API {@link ApiExtraction#joinOn()} lists
 * the exact join columns.
 */
public record Merge(String key) {
}
