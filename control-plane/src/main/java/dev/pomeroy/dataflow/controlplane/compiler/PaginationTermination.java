package dev.pomeroy.dataflow.controlplane.compiler;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * When a page loop stops, in the plan's own vocabulary. {@code totalPages}: every
 * page's envelope carries {@code totalPages}; stop after fetching that page number.
 */
public enum PaginationTermination {

	@JsonProperty("totalPages")
	TOTAL_PAGES
}
