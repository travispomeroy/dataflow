package dev.pomeroy.dataflow.controlplane.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The paging scheme of an upstream API. {@code PAGE_NUMBER}: {@code ?page=N&pageSize=M}
 * with pages starting at 1 (the only style in the mock world).
 */
public enum PaginationStyle {

	@JsonProperty("pageNumber")
	PAGE_NUMBER
}
