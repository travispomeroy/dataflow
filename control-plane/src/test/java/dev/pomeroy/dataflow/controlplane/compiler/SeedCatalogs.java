package dev.pomeroy.dataflow.controlplane.compiler;

import dev.pomeroy.dataflow.controlplane.catalog.Catalog;
import dev.pomeroy.dataflow.controlplane.catalog.Client;
import dev.pomeroy.dataflow.controlplane.catalog.Destination;
import dev.pomeroy.dataflow.controlplane.catalog.FileDefinition;
import dev.pomeroy.dataflow.controlplane.catalog.Source;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link Catalog} over the same committed seed resources the real module serves —
 * loaded through the catalog's public record types so compiler tests exercise the
 * world that actually exists, without a Spring context or containers.
 */
final class SeedCatalogs {

	private SeedCatalogs() {
	}

	static Catalog fromCommittedSeeds() {
		ObjectMapper mapper = JsonMapper.builder().build();
		List<Source> sources = load(mapper, "sources.json", new TypeReference<>() {
		});
		List<Destination> destinations = load(mapper, "destinations.json", new TypeReference<>() {
		});
		List<FileDefinition> fileDefinitions = load(mapper, "file-definitions.json",
				new TypeReference<>() {
				});
		List<Client> clients = load(mapper, "clients.json", new TypeReference<>() {
		});
		return new Catalog() {

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
		};
	}

	private static <T> List<T> load(ObjectMapper mapper, String seed, TypeReference<List<T>> type) {
		try (InputStream in = Catalog.class.getResourceAsStream("internal/seeds/" + seed)) {
			return mapper.readValue(in, type);
		}
		catch (IOException e) {
			throw new UncheckedIOException("cannot load committed seed " + seed, e);
		}
	}
}
