package dev.pomeroy.dataflow.controlplane.compiler;

/**
 * How one upstream API pages, in the plan's own vocabulary — every engine compiler
 * generates a page loop from exactly this.
 */
public record Pagination(PaginationStyle style, int pageSize, PaginationTermination termination) {
}
