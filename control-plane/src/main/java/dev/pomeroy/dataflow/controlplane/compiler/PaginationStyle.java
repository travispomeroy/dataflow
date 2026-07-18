package dev.pomeroy.dataflow.controlplane.compiler;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The paging scheme of an upstream API, in the plan's own vocabulary.
 * {@code pageNumber}: {@code ?page=N&pageSize=M} with pages starting at 1.
 */
public enum PaginationStyle {

	@JsonProperty("pageNumber")
	PAGE_NUMBER
}
