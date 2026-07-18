package dev.pomeroy.dataflow.controlplane.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * When a page loop stops. {@code TOTAL_PAGES}: every page's envelope carries
 * {@code totalPages}; stop after fetching that page number.
 */
public enum PaginationTermination {

	@JsonProperty("totalPages")
	TOTAL_PAGES
}
