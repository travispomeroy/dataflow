package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DeploymentCompiler.DeployViolation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A rejected deploy: RFC 9457 problem details carrying the structured semantic
 * violations array (ADR-0005) — each entry a stable rule id plus a message, the shape
 * the M3 canvas consumes.
 */
class SemanticViolationsException extends ErrorResponseException {

	SemanticViolationsException(List<DeployViolation> violations) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, problemDetail(violations), null);
	}

	private static ProblemDetail problemDetail(List<DeployViolation> violations) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
				"The Dataflow Config is not deployable");
		problem.setTitle("Semantic validation failed");
		problem.setProperty("violations", violations);
		return problem;
	}
}
