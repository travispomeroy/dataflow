package dev.pomeroy.dataflow.controlplane.catalog;

/**
 * How one upstream API pages: every engine compiler must generate a page loop from
 * this — single-page extraction is never special-cased (standing rule).
 */
public record Pagination(PaginationStyle style, int pageSize, PaginationTermination termination) {
}
