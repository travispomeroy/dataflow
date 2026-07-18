package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Draft → Deploy lifecycle endpoints (issue #16). Deploy always deploys the Draft
 * as it stands right now — the Draft may then drift ahead of the active Deployment
 * until the next deploy freezes it again.
 */
@RestController
@RequestMapping("/api/dataflows/{id}")
class DeploymentRestController {

	private final DataflowRepository dataflows;

	private final DeploymentRepository deployments;

	private final DeploymentService service;

	private final ObjectMapper mapper;

	DeploymentRestController(DataflowRepository dataflows, DeploymentRepository deployments,
			DeploymentService service, ObjectMapper mapper) {
		this.dataflows = dataflows;
		this.deployments = deployments;
		this.service = service;
		this.mapper = mapper;
	}

	@PostMapping("/deploy")
	ResponseEntity<DeploymentResponse> deploy(@PathVariable UUID id) {
		DeploymentEntity deployment = service.deploy(find(id));
		return ResponseEntity
				.created(URI.create("/api/dataflows/" + id + "/deployments/" + deployment.version()))
				.body(DeploymentResponse.of(deployment));
	}

	@PostMapping("/undeploy")
	ResponseEntity<Void> undeploy(@PathVariable UUID id) {
		service.undeploy(find(id));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/deployments")
	List<DeploymentResponse> history(@PathVariable UUID id) {
		return deployments.findByDataflowIdOrderByVersionDesc(find(id).id()).stream()
				.map(DeploymentResponse::of).toList();
	}

	@GetMapping("/deployments/{version}")
	FrozenDeploymentResponse deployment(@PathVariable UUID id, @PathVariable int version) {
		DeploymentEntity deployment = deployments.findByDataflowIdAndVersion(find(id).id(), version)
				.orElseThrow(() -> notFound(
						"No Deployment version %d for Dataflow '%s'".formatted(version, id)));
		return FrozenDeploymentResponse.of(deployment, mapper);
	}

	private DataflowEntity find(UUID id) {
		return dataflows.findById(id)
				.orElseThrow(() -> notFound("No Dataflow with id '%s'".formatted(id)));
	}

	private static ErrorResponseException notFound(String detail) {
		return new ErrorResponseException(HttpStatus.NOT_FOUND,
				ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail), null);
	}

	record DeploymentResponse(int version, Instant deployedAt, boolean active) {

		static DeploymentResponse of(DeploymentEntity deployment) {
			return new DeploymentResponse(deployment.version(), deployment.deployedAt(),
					deployment.active());
		}
	}

	/** A frozen Deployment in full: the config copy and Execution Plan snapshot. */
	record FrozenDeploymentResponse(int version, Instant deployedAt, boolean active,
			DataflowConfig config, JsonNode plan) {

		static FrozenDeploymentResponse of(DeploymentEntity deployment, ObjectMapper mapper) {
			return new FrozenDeploymentResponse(deployment.version(), deployment.deployedAt(),
					deployment.active(), deployment.config(),
					mapper.readTree(deployment.plan().json()));
		}
	}
}
