package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dataflow draft CRUD. The config arrives as raw JSON and is parsed here, so an
 * unparseable document (unknown node type, malformed shape) lands in the same
 * violations array as the structural checks instead of a bare 400. The lifecycle
 * endpoints live in {@link DeploymentRestController}; run-now is a separate seam
 * (M1.7).
 */
@RestController
@RequestMapping("/api/dataflows")
class DataflowRestController {

	private final DataflowRepository repository;

	private final StructuralValidator validator;

	private final DeploymentService deployments;

	private final ObjectMapper mapper;

	DataflowRestController(DataflowRepository repository, StructuralValidator validator,
			DeploymentService deployments, ObjectMapper mapper) {
		this.repository = repository;
		this.validator = validator;
		this.deployments = deployments;
		this.mapper = mapper;
	}

	@PostMapping
	ResponseEntity<DataflowResponse> create(@RequestBody SaveDataflowRequest request) {
		String slug = mintSlug(request.name());
		DataflowConfig config = validated(request, nameViolations(request.name()));
		DataflowEntity saved;
		try {
			saved = repository.save(new DataflowEntity(null, slug, request.name(), config));
		}
		catch (DuplicateKeyException e) {
			// The unique constraint is the authority, so a concurrent create can
			// never slip past a pre-check into a 500.
			throw problem(HttpStatus.CONFLICT,
					"A Dataflow with slug '%s' already exists".formatted(slug));
		}
		return ResponseEntity.created(URI.create("/api/dataflows/" + saved.id()))
				.body(DataflowResponse.of(saved));
	}

	/**
	 * The list is a card-ready summary projection (issue #29), not the config
	 * documents: deployment fact, drift flag, latest Run — the builder fetches the full
	 * Draft from {@code GET /{id}}.
	 */
	@GetMapping
	List<DataflowSummary> list() {
		return repository.summaries();
	}

	@GetMapping("/{id}")
	DataflowResponse get(@PathVariable UUID id) {
		return DataflowResponse.of(find(id));
	}

	@PutMapping("/{id}")
	DataflowResponse update(@PathVariable UUID id, @RequestBody SaveDataflowRequest request) {
		DataflowEntity existing = find(id);
		DataflowConfig config = validated(request, nameViolations(request.name()));
		// The slug is deliberately carried over untouched: renames change display
		// name only; the slug is the Dataflow's identity for life (spec #10).
		DataflowEntity saved = repository
				.save(new DataflowEntity(existing.id(), existing.slug(), request.name(), config));
		return DataflowResponse.of(saved);
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable UUID id) {
		DataflowEntity existing = find(id);
		// Stopping a feed is always an explicit, separate act (spec #10): a deployed
		// Dataflow must be undeployed before it can be deleted.
		if (deployments.deployed(existing.id())) {
			throw problem(HttpStatus.CONFLICT,
					"Dataflow '%s' is deployed — undeploy it first".formatted(existing.slug()));
		}
		repository.delete(existing);
		return ResponseEntity.noContent().build();
	}

	private DataflowEntity find(UUID id) {
		return repository.findById(id).orElseThrow(
				() -> problem(HttpStatus.NOT_FOUND, "No Dataflow with id '%s'".formatted(id)));
	}

	private static ErrorResponseException problem(HttpStatus status, String detail) {
		return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail),
				null);
	}

	/**
	 * One name rule for create and rename alike: the name must be able to mint a slug,
	 * even though only creation actually mints one.
	 */
	private static List<Violation> nameViolations(String name) {
		return mintSlug(name) == null
				? List.of(new Violation("name must contain at least one letter or digit"))
				: List.of();
	}

	/**
	 * Parses and structurally validates the submitted config; throws the 422 problem
	 * detail carrying every collected violation (including the given upfront ones).
	 */
	private DataflowConfig validated(SaveDataflowRequest request, List<Violation> upfront) {
		List<Violation> violations = new ArrayList<>(upfront);
		DataflowConfig config = null;
		if (request.config() == null || request.config().isNull()) {
			violations.add(new Violation("config is required"));
		}
		else {
			try {
				config = mapper.treeToValue(request.config(), DataflowConfig.class);
				violations.addAll(validator.validate(config));
			}
			catch (DatabindException e) {
				violations.add(new Violation(e.getOriginalMessage()));
			}
			catch (JacksonException e) {
				violations.add(new Violation(e.getMessage()));
			}
		}
		if (!violations.isEmpty()) {
			throw new StructuralViolationsException(violations);
		}
		return config;
	}

	/**
	 * The immutable slug, minted once from the initial name; {@code null} when the
	 * name has nothing to mint from.
	 */
	private static String mintSlug(String name) {
		if (name == null) {
			return null;
		}
		String slug = name.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+|-+$", "");
		return slug.isEmpty() ? null : slug;
	}

	record SaveDataflowRequest(String name, JsonNode config) {
	}

	record DataflowResponse(UUID id, String slug, String name, DataflowConfig config) {

		static DataflowResponse of(DataflowEntity entity) {
			return new DataflowResponse(entity.id(), entity.slug(), entity.name(), entity.config());
		}
	}
}
