package dev.pomeroy.dataflow.controlplane.catalog;

import java.util.List;

/**
 * The latest-record collapse rule of a merging API (ADR-0006): of the records sharing
 * one {@link UpstreamApi#joinOn()} key, only the latest survives the merge —
 * {@code latestBy} names the deciding upstream fields, max of the first, later ones
 * breaking ties. Base rows without a match keep empty columns; records without a base
 * row drop.
 */
public record Collapse(List<String> latestBy) {
}
