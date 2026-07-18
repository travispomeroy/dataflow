package dev.pomeroy.dataflow.controlplane.catalog.internal;

import dev.pomeroy.dataflow.controlplane.catalog.Catalog;
import dev.pomeroy.dataflow.controlplane.catalog.FileDefinition;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Catalog read API. Responses carry logical names only — physical definitions
 * (endpoints, credentials, merge mechanics) stay behind the module's Java API.
 */
@RestController
@RequestMapping("/api/catalog")
class CatalogRestController {

	private final Catalog catalog;

	CatalogRestController(Catalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/sources")
	List<SourceSummary> sources() {
		return catalog.sources().stream()
				.map(source -> new SourceSummary(source.id(), source.name(), source.description()))
				.toList();
	}

	@GetMapping("/destinations")
	List<DestinationSummary> destinations() {
		return catalog.destinations().stream()
				.map(destination -> new DestinationSummary(destination.id(), destination.name(),
						destination.description()))
				.toList();
	}

	@GetMapping("/file-definitions")
	List<FileDefinition> fileDefinitions() {
		return catalog.fileDefinitions();
	}

	record SourceSummary(String id, String name, String description) {
	}

	record DestinationSummary(String id, String name, String description) {
	}
}
