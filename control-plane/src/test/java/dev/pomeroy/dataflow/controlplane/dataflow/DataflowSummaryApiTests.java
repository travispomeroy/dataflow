package dev.pomeroy.dataflow.controlplane.dataflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pomeroy.dataflow.controlplane.TestcontainersConfiguration;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The card-ready list projection (issue #29): {@code GET /api/dataflows} serves the
 * status board in one request — deployment fact, drift flag, latest Run — while the
 * config documents stay behind {@code GET /{id}}. The silent Kestra stand-in below
 * lets deploy and run-now succeed without the compose world.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, DataflowSummaryApiTests.KestraSilence.class })
class DataflowSummaryApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	SilentKestraClient kestra;

	ObjectMapper mapper = new ObjectMapper();

	String draft(String name, String configJson) {
		return "{\"name\": " + mapper.writeValueAsString(name) + ", \"config\": " + configJson + "}";
	}

	String canonicalConfig() throws Exception {
		return Files.readString(Path.of("../e2e/canonical/positions-feed.config.json"));
	}

	String create(String name) throws Exception {
		String response = mvc
				.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
						.content(draft(name, canonicalConfig())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return mapper.readTree(response).path("id").stringValue();
	}

	/** This Dataflow's card, filtered out of the one list response. */
	JsonNode card(String id) throws Exception {
		String list = mvc.perform(get("/api/dataflows")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		for (JsonNode row : mapper.readTree(list)) {
			if (row.path("id").stringValue().equals(id)) {
				return row;
			}
		}
		throw new AssertionError("Dataflow " + id + " is missing from the list");
	}

	/**
	 * The drift flag across the whole lifecycle (the issue's acceptance walk):
	 * never-deployed → deployed-clean → draft-edited → redeployed → undeployed.
	 */
	@Test
	void draftDriftedFollowsTheLifecycle() throws Exception {
		String id = create("Drift Walk");

		// Never deployed: no deployment fact, no drift — there is nothing to drift from.
		JsonNode card = card(id);
		assertThat(card.path("activeDeploymentVersion").isNull()).isTrue();
		assertThat(card.path("draftDrifted").booleanValue()).isFalse();

		// Deployed: the Draft and the frozen copy are the same document.
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());
		card = card(id);
		assertThat(card.path("activeDeploymentVersion").intValue()).isEqualTo(1);
		assertThat(card.path("draftDrifted").booleanValue()).isFalse();

		// Draft edited: drifted ahead of the still-active v1.
		mvc.perform(put("/api/dataflows/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(draft("Drift Walk", canonicalConfig().replace("06:30", "07:15"))))
				.andExpect(status().isOk());
		card = card(id);
		assertThat(card.path("activeDeploymentVersion").intValue()).isEqualTo(1);
		assertThat(card.path("draftDrifted").booleanValue()).isTrue();

		// Redeployed: v2 froze the edited Draft — clean again.
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());
		card = card(id);
		assertThat(card.path("activeDeploymentVersion").intValue()).isEqualTo(2);
		assertThat(card.path("draftDrifted").booleanValue()).isFalse();

		// Undeployed: no active Deployment means no deployment fact and never a drift flag.
		mvc.perform(post("/api/dataflows/" + id + "/undeploy")).andExpect(status().isNoContent());
		card = card(id);
		assertThat(card.path("activeDeploymentVersion").isNull()).isTrue();
		assertThat(card.path("draftDrifted").booleanValue()).isFalse();
	}

	/**
	 * The last-run summary is the runs API's newest entry, field for field — and null
	 * while nothing has ever run.
	 */
	@Test
	void lastRunMatchesTheRunsApiAndIsAbsentWhenNothingRan() throws Exception {
		String id = create("Last Run Feed");
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());

		assertThat(card(id).path("lastRun").isNull()).isTrue();

		mvc.perform(post("/api/dataflows/" + id + "/run-now")).andExpect(status().isAccepted());
		// The newest run comes back already terminal, so the summary's endedAt has a
		// real value to agree on — not just two nulls.
		kestra.nextExecutionSucceeds = true;
		mvc.perform(post("/api/dataflows/" + id + "/run-now")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"businessDate\": \"2026-07-15\"}"))
				.andExpect(status().isAccepted());

		String history = mvc.perform(get("/api/dataflows/" + id + "/runs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andReturn().getResponse().getContentAsString();
		JsonNode newest = mapper.readTree(history).get(0);

		JsonNode lastRun = card(id).path("lastRun");
		assertThat(lastRun.path("status").stringValue())
				.isEqualTo(newest.path("status").stringValue());
		assertThat(lastRun.path("startedAt").stringValue())
				.isEqualTo(newest.path("startedAt").stringValue());
		assertThat(lastRun.path("endedAt").stringValue())
				.isEqualTo(newest.path("endedAt").stringValue());
		assertThat(lastRun.path("businessDate").stringValue())
				.isEqualTo(newest.path("businessDate").stringValue());
		assertThat(lastRun.path("status").stringValue()).isEqualTo("SUCCEEDED");
		assertThat(lastRun.path("endedAt").stringValue()).isNotEmpty();
		assertThat(lastRun.path("businessDate").stringValue()).isEqualTo("2026-07-15");
	}

	/** Config documents drop out of the list — the builder fetches {@code GET /{id}}. */
	@Test
	void theListCarriesNoConfigDocumentsAndTheDetailEndpointStillDoes() throws Exception {
		String id = create("Summary Only Feed");

		JsonNode card = card(id);
		assertThat(card.has("config")).isFalse();
		assertThat(card.path("slug").stringValue()).isEqualTo("summary-only-feed");
		assertThat(card.path("name").stringValue()).isEqualTo("Summary Only Feed");

		mvc.perform(get("/api/dataflows/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.config.nodes").isArray())
				.andExpect(jsonPath("$.config.schedule.time").value("06:30"));
	}

	// --- the silent stand-in for live Kestra ---

	@TestConfiguration(proxyBeanMethods = false)
	static class KestraSilence {

		@Bean
		@Primary
		SilentKestraClient silentKestraClient() {
			return new SilentKestraClient();
		}
	}

	static class SilentKestraClient implements KestraClient {

		// Kestra execution IDs are globally unique; the run upsert is keyed by them,
		// so the fake must never mint the same ID twice across tests.
		private static final AtomicInteger SEQUENCE = new AtomicInteger();

		/** One-shot: the next created execution comes back already terminal SUCCESS. */
		boolean nextExecutionSucceeds;

		@Override
		public void putFlow(String flowId, String flowYaml) {
		}

		@Override
		public void deleteFlow(String flowId) {
		}

		@Override
		public KestraExecution createExecution(String flowId, Map<String, String> inputs) {
			int sequence = SEQUENCE.incrementAndGet();
			boolean succeeds = nextExecutionSucceeds;
			nextExecutionSucceeds = false;
			Instant startDate = Instant.parse("2026-07-18T06:30:00Z").plusSeconds(sequence);
			// Live Kestra echoes the execution's inputs back in the created document.
			return new KestraExecution("summary-exec-" + sequence, flowId,
					succeeds ? "SUCCESS" : "CREATED", startDate,
					succeeds ? startDate.plusSeconds(30) : null,
					inputs.containsKey("businessDate")
							? LocalDate.parse(inputs.get("businessDate")) : null);
		}

		@Override
		public List<KestraExecution> listExecutions() {
			return List.of();
		}

		@Override
		public Map<String, Object> taskOutputVars(String executionId, String taskId) {
			return Map.of();
		}
	}
}
