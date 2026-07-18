package dev.pomeroy.dataflow.controlplane.catalog.internal;

import dev.pomeroy.dataflow.controlplane.catalog.Catalog;
import dev.pomeroy.dataflow.controlplane.catalog.Client;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client reference data for the filter UI. Reference data, not a Catalog entry, so it
 * lives at {@code /api/clients} rather than under {@code /api/catalog}.
 */
@RestController
class ClientsRestController {

	private final Catalog catalog;

	ClientsRestController(Catalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/api/clients")
	List<Client> clients() {
		return catalog.clients();
	}
}
