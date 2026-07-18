package dev.pomeroy.dataflow.controlplane.compiler;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One upstream API to extract from, pagination resolved so engine compilers can
 * generate the page loop (single-page extraction is never special-cased — standing
 * rule). {@code joinOn} is empty for the base API; {@code fields} projects upstream
 * field names onto logical output fields, in projection order; {@code collapse} is
 * the latest-record rule for a one-to-many merge, absent from the plan document when
 * every join key matches at most one record.
 */
public record ApiExtraction(String name, String path, List<String> joinOn,
		Pagination pagination, Map<String, String> fields,
		@JsonInclude(JsonInclude.Include.NON_NULL) Collapse collapse) {

	public ApiExtraction {
		joinOn = joinOn == null ? List.of() : List.copyOf(joinOn);
		// LinkedHashMap, not Map.copyOf — projection order is part of the contract.
		fields = fields == null ? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}
}
