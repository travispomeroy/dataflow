package dev.pomeroy.dataflow.controlplane.catalog.internal;

import dev.pomeroy.dataflow.controlplane.catalog.Catalog;
import dev.pomeroy.dataflow.controlplane.catalog.Client;
import dev.pomeroy.dataflow.controlplane.catalog.Destination;
import dev.pomeroy.dataflow.controlplane.catalog.FileDefinition;
import dev.pomeroy.dataflow.controlplane.catalog.Source;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The file-backed Catalog: committed seed resources under {@code seeds/}, loaded once
 * at startup. There is deliberately no store and no write path.
 */
@Service
class SeedCatalog implements Catalog {

	private final List<Source> sources;

	private final List<Destination> destinations;

	private final List<FileDefinition> fileDefinitions;

	private final List<Client> clients;

	SeedCatalog(ObjectMapper mapper) {
		this.sources = load(mapper, "sources.json", new TypeReference<>() {
		});
		this.destinations = load(mapper, "destinations.json", new TypeReference<>() {
		});
		this.fileDefinitions = load(mapper, "file-definitions.json", new TypeReference<>() {
		});
		this.clients = load(mapper, "clients.json", new TypeReference<>() {
		});
	}

	@Override
	public List<Source> sources() {
		return sources;
	}

	@Override
	public List<Destination> destinations() {
		return destinations;
	}

	@Override
	public List<FileDefinition> fileDefinitions() {
		return fileDefinitions;
	}

	@Override
	public List<Client> clients() {
		return clients;
	}

	private <T> List<T> load(ObjectMapper mapper, String seed, TypeReference<List<T>> type) {
		try (InputStream in = SeedCatalog.class.getResourceAsStream("seeds/" + seed)) {
			if (in == null) {
				throw new IllegalStateException("Missing catalog seed resource: " + seed);
			}
			return List.copyOf(mapper.readValue(in, type));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Unreadable catalog seed resource: " + seed, e);
		}
	}
}
