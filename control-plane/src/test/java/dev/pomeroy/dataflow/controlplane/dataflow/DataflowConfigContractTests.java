package dev.pomeroy.dataflow.controlplane.dataflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The canonical Positions Feed config example is the contract everything downstream
 * reuses: the compiler goldens (M1.4/M1.5) start from it and the mirrored TS types
 * (M1.3) type-check it. This test holds the Java side honest: the schema records must
 * represent every canonical byte with nothing lost, added, or renamed in either
 * direction. Surefire runs with the module base directory as working directory, so the
 * repo root is one level up.
 */
class DataflowConfigContractTests {

	static final Path CANONICAL_EXAMPLE =
			Path.of("../e2e/canonical/positions-feed.config.json");

	ObjectMapper mapper = JsonMapper.builder().addModule(new DataflowConfigModule()).build();

	@Test
	void canonicalPositionsFeedRoundTripsThroughTheConfigRecordsWithFullFidelity() throws Exception {
		String json = Files.readString(CANONICAL_EXAMPLE);

		DataflowConfig config = mapper.readValue(json, DataflowConfig.class);
		JsonNode reserialized = mapper.valueToTree(config);

		assertThat(reserialized).isEqualTo(mapper.readTree(json));
	}

	@Test
	void canonicalPositionsFeedIsTheStraightLineThroughTheCatalogWithADailySchedule() throws Exception {
		DataflowConfig config = mapper.readValue(Files.readString(CANONICAL_EXAMPLE),
				DataflowConfig.class);

		assertThat(config.nodes()).containsExactly(
				new SourceNode("positions-source", "source", "positions"),
				new ClientFilterNode("client-filter", "transform", "clientFilter",
						List.of("INV-001", "INV-002", "INV-003")),
				new DestinationNode("pomeroy-delivery", "destination", "pomeroy-provider"));
		assertThat(config.edges()).containsExactly(
				new Edge("positions-source", "client-filter"),
				new Edge("client-filter", "pomeroy-delivery"));
		assertThat(config.schedule())
				.isEqualTo(new Schedule(ScheduleKind.DAILY, "06:30", "America/New_York"));
		assertThat(config.engine()).isEqualTo(Engine.HOP);
		assertThat(config.executionModel()).isEqualTo(ExecutionModel.BATCH);
	}

	@Test
	void aManualOnlyHalfBuiltDraftRoundTripsWithItsNullScheduleAndOperatorFieldsIntact() throws Exception {
		String halfBuilt = """
				{
				  "nodes": [
				    { "id": "positions-source", "type": "source", "sourceId": "positions" }
				  ],
				  "edges": [],
				  "schedule": null,
				  "engine": null,
				  "executionModel": null
				}
				""";

		DataflowConfig config = mapper.readValue(halfBuilt, DataflowConfig.class);

		assertThat(config.schedule()).isNull();
		assertThat(config.engine()).isNull();
		assertThat(config.executionModel()).isNull();
		JsonNode reserialized = mapper.valueToTree(config);
		assertThat(reserialized).isEqualTo(mapper.readTree(halfBuilt));
	}
}
