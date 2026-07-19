package dev.pomeroy.dataflow.controlplane.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pomeroy.dataflow.controlplane.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The catalog's committed seeds against the mock world's committed fixtures — the seeds
 * must describe the world that actually exists in {@code infra/} (issue #11). Surefire
 * runs with the module base directory as working directory, so the repo root is one
 * level up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogSeedConsistencyTests {

	static final Path REPO_ROOT = Path.of("..");

	@Autowired
	MockMvc mvc;

	@Autowired
	Catalog catalog;

	ObjectMapper mapper = new ObjectMapper();

	@Test
	void clientsAreExactlyTheFixtureInvestorsRenamedToClientVocabulary() throws Exception {
		List<Map<String, String>> investors = mapper.readValue(
				Files.readString(REPO_ROOT.resolve("infra/fixtures/data/investors.json")),
				new TypeReference<>() {
				});
		List<Map<String, String>> expectedClients = investors.stream()
				.map(investor -> Map.of(
						"id", investor.get("investorId"),
						"name", investor.get("name"),
						"advisorGroup", investor.get("advisorGroup")))
				.toList();

		String body = mvc.perform(get("/api/clients"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<Map<String, String>> served = mapper.readValue(body, new TypeReference<>() {
		});

		assertThat(served).containsExactlyElementsOf(expectedClients);
	}

	/**
	 * A File Definition can only deliver columns its Source actually produces: every
	 * entry in {@code columns} must be a logical field some upstream API projects.
	 */
	@Test
	void fileDefinitionColumnsAreFieldsTheirSourceProjects() {
		for (FileDefinition definition : catalog.fileDefinitions()) {
			Source source = catalog.sources().stream()
					.filter(candidate -> candidate.id().equals(definition.sourceId()))
					.findFirst().orElseThrow();
			List<String> projected = source.physical().apis().stream()
					.flatMap(api -> api.fields().values().stream())
					.toList();

			assertThat(definition.columns())
					.as("columns of file definition %s", definition.id())
					.isNotEmpty()
					.isSubsetOf(projected);
		}
	}

	/**
	 * The Asset Class domain the seed declares must be the one the fixture world
	 * actually exhibits: {@code splitValues} is exactly the distinct values the split
	 * field takes across the fixture positions — no phantom class, no missing one.
	 */
	@Test
	void fileDefinitionSplitValuesAreTheFixturesDistinctSplitFieldValues() throws Exception {
		List<Map<String, Object>> positions = mapper.readValue(
				Files.readString(REPO_ROOT.resolve("infra/fixtures/data/positions.json")),
				new TypeReference<>() {
				});
		List<String> fixtureClasses = positions.stream()
				.map(position -> (String) position.get("assetClass"))
				.distinct().sorted().toList();

		FileDefinition definition = catalog.fileDefinitions().stream()
				.filter(candidate -> candidate.id().equals("positions-by-asset-class"))
				.findFirst().orElseThrow();

		assertThat(definition.splitValues()).isEqualTo(fixtureClasses);
	}

	/**
	 * The mock world's one SFTP secret is committed in infra/.env; the catalog must
	 * reference it by name (a Kestra secret ref) and never carry its value — not in a
	 * seed resource, not in a response (issue #11; ADR-0002 as amended).
	 */
	@Test
	void secretMaterialAppearsInNoSeedResourceAndNoResponse() throws Exception {
		String sftpPassword = Files.readAllLines(REPO_ROOT.resolve("infra/.env")).stream()
				.filter(line -> line.startsWith("SFTP_PASSWORD="))
				.map(line -> line.substring("SFTP_PASSWORD=".length()))
				.findFirst().orElseThrow();

		for (String seed : List.of("sources.json", "destinations.json", "file-definitions.json",
				"clients.json")) {
			try (var in = Catalog.class
					.getResourceAsStream("internal/seeds/" + seed)) {
				assertThat(new String(in.readAllBytes()))
						.as("seed resource %s", seed)
						.doesNotContain(sftpPassword);
			}
		}

		for (String endpoint : List.of("/api/catalog/sources", "/api/catalog/destinations",
				"/api/catalog/file-definitions", "/api/clients")) {
			String body = mvc.perform(get(endpoint))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			assertThat(body).as("response of %s", endpoint).doesNotContain(sftpPassword);
		}
	}
}
