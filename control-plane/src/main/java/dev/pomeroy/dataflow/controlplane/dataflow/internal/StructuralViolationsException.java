package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A rejected save: RFC 9457 problem details with the structured violations array
 * (spec #10) — the same shape Deploy's semantic 422 uses from M1.6.
 */
class StructuralViolationsException extends ErrorResponseException {

	StructuralViolationsException(List<Violation> violations) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, problemDetail(violations), null);
	}

	private static ProblemDetail problemDetail(List<Violation> violations) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
				"The Dataflow Config is not structurally valid");
		problem.setTitle("Structural validation failed");
		problem.setProperty("violations", violations);
		return problem;
	}
}
