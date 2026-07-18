package dev.pomeroy.dataflow.controlplane.dataflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pomeroy.dataflow.controlplane.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The Draft → Deploy lifecycle seam (issue #16): deploy freezes an immutable
 * Deployment and publishes the flow through the {@link OrchestratorFlows} port; the
 * recording fake below stands where live Kestra does, so these tests assert exactly
 * what the Orchestrator is told, byte for byte, without the compose world.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, DeployLifecycleApiTests.OrchestratorRecording.class })
class DeployLifecycleApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	RecordingOrchestratorFlows orchestrator;

	ObjectMapper mapper = new ObjectMapper();

	@BeforeEach
	void resetOrchestrator() {
		orchestrator.reset();
	}

	String canonicalDraft(String name) throws Exception {
		return draft(name, Files.readString(Path.of("../e2e/canonical/positions-feed.config.json")));
	}

	String draft(String name, String configJson) {
		return "{\"name\": " + mapper.writeValueAsString(name) + ", \"config\": " + configJson + "}";
	}

	String create(String body) throws Exception {
		return mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	String idOf(String responseBody) {
		return mapper.readTree(responseBody).path("id").stringValue();
	}

	// --- deploy ---

	@Test
	void deployingTheCanonicalDraftFreezesDeploymentV1AndPublishesTheGoldenFlow() throws Exception {
		String id = idOf(create(canonicalDraft("Positions Feed")));

		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/dataflows/" + id + "/deployments/1"))
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.deployedAt").isNotEmpty());

		// The published flow is byte-identical to the committed golden: stable identity
		// (id = slug, namespace dataflow) and the Deployment version as the label.
		assertThat(orchestrator.puts).containsExactly(new FlowPut("positions-feed",
				Files.readString(Path.of("../e2e/golden/positions-feed.flow.yaml"))));

		// And the frozen Execution Plan snapshot is the committed golden plan — the
		// audit record of exactly what this version will do (ADR-0002).
		String detail = mvc.perform(get("/api/dataflows/" + id + "/deployments/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(1))
				.andReturn().getResponse().getContentAsString();
		assertThat(mapper.readTree(detail).path("plan")).isEqualTo(mapper.readTree(
				Files.readString(Path.of("../e2e/golden/positions-feed.plan.json"))));
	}

	@Test
	void deployingAnInvalidGraphIsRejectedWithStructuredViolationsAndFreezesNothing() throws Exception {
		// A lone source and a disconnected filter: no Delivery, not one component.
		String id = idOf(create(draft("Half Built", """
				{
				  "nodes": [
				    { "id": "src", "type": "source", "sourceId": "positions" },
				    { "id": "flt", "type": "transform", "kind": "clientFilter",
				      "clientIds": ["INV-001"] }
				  ],
				  "edges": []
				}
				""")));

		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(422))
				.andExpect(jsonPath("$.violations[*].rule", hasItem("delivery-count")))
				.andExpect(jsonPath("$.violations[*].rule", hasItem("disconnected")))
				.andExpect(jsonPath("$.violations[*].message", not(hasItem(emptyString()))));

		assertThat(orchestrator.puts).isEmpty();

		// The rejected attempt froze nothing: fixing the Draft and deploying mints v1.
		mvc.perform(put("/api/dataflows/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(canonicalDraft("Half Built"))).andExpect(status().isOk());
		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(1));
	}

	@Test
	void aDiamondGraphIsRejectedAsNonLinearUntilM7() throws Exception {
		String id = idOf(create(draft("Diamond Feed", """
				{
				  "nodes": [
				    { "id": "src", "type": "source", "sourceId": "positions" },
				    { "id": "f1", "type": "transform", "kind": "clientFilter", "clientIds": ["INV-001"] },
				    { "id": "f2", "type": "transform", "kind": "clientFilter", "clientIds": ["INV-002"] },
				    { "id": "dst", "type": "destination", "destinationId": "pomeroy-provider" }
				  ],
				  "edges": [
				    { "from": "src", "to": "f1" }, { "from": "src", "to": "f2" },
				    { "from": "f1", "to": "dst" }, { "from": "f2", "to": "dst" }
				  ],
				  "engine": "hop",
				  "executionModel": "batch"
				}
				""")));

		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.violations[*].rule", hasItem("non-linear")));

		assertThat(orchestrator.puts).isEmpty();
	}

	@Test
	void redeployingAfterADraftEditCreatesV2AndOverwritesTheSameFlow() throws Exception {
		String id = idOf(create(canonicalDraft("Redeployed Feed")));
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());

		String laterSchedule = Files
				.readString(Path.of("../e2e/canonical/positions-feed.config.json"))
				.replace("06:30", "07:15");
		mvc.perform(put("/api/dataflows/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(draft("Positions Feed", laterSchedule))).andExpect(status().isOk());

		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/dataflows/" + id + "/deployments/2"))
				.andExpect(jsonPath("$.version").value(2));

		// Same stable flow identity both times — deploy is a single overwrite — with
		// the version label moved on and the edited schedule compiled in.
		assertThat(orchestrator.puts).hasSize(2);
		assertThat(orchestrator.puts.getFirst().slug()).isEqualTo("redeployed-feed");
		assertThat(orchestrator.puts.getLast().slug()).isEqualTo("redeployed-feed");
		assertThat(orchestrator.puts.getLast().flowYaml())
				.contains("dataflow.version: \"2\"")
				.contains("cron: \"15 7 * * *\"");
	}

	// --- deployment history ---

	@Test
	void deploymentHistoryListsVersionsNewestFirstWithTimestamps() throws Exception {
		String id = idOf(create(canonicalDraft("Storied Feed")));
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());

		mvc.perform(get("/api/dataflows/" + id + "/deployments"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].version").value(2))
				.andExpect(jsonPath("$[0].active").value(true))
				.andExpect(jsonPath("$[0].deployedAt").isNotEmpty())
				.andExpect(jsonPath("$[1].version").value(1))
				.andExpect(jsonPath("$[1].active").value(false))
				.andExpect(jsonPath("$[1].deployedAt").isNotEmpty());
	}

	@Test
	void aFrozenDeploymentIsImmuneToDraftDrift() throws Exception {
		String id = idOf(create(canonicalDraft("Drifting Feed")));
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());

		String editedDraft = Files.readString(Path.of("../e2e/canonical/positions-feed.config.json"))
				.replace("06:30", "07:15");
		mvc.perform(put("/api/dataflows/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(draft("Drifting Feed", editedDraft))).andExpect(status().isOk());

		// The Draft drifted ahead; the frozen config copy still says 06:30.
		mvc.perform(get("/api/dataflows/" + id))
				.andExpect(jsonPath("$.config.schedule.time").value("07:15"));
		mvc.perform(get("/api/dataflows/" + id + "/deployments/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.config.schedule.time").value("06:30"))
				.andExpect(jsonPath("$.plan.schedule.time").value("06:30"));

		mvc.perform(get("/api/dataflows/" + id + "/deployments/9"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	// --- undeploy, delete guard, failure atomicity ---

	@Test
	void undeployRemovesTheFlowAndKeepsEveryFrozenSnapshot() throws Exception {
		String id = idOf(create(canonicalDraft("Retiring Feed")));
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());

		mvc.perform(post("/api/dataflows/" + id + "/undeploy")).andExpect(status().isNoContent());

		assertThat(orchestrator.removes).containsExactly("retiring-feed");
		// Kestra no longer holds the flow; the audit surface is untouched.
		mvc.perform(get("/api/dataflows/" + id + "/deployments"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].version").value(1))
				.andExpect(jsonPath("$[0].active").value(false));
		mvc.perform(get("/api/dataflows/" + id + "/deployments/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.plan.slug").value("retiring-feed"));
	}

	@Test
	void undeployingAnUndeployedDataflowIsAConflict() throws Exception {
		String id = idOf(create(canonicalDraft("Never Deployed")));

		mvc.perform(post("/api/dataflows/" + id + "/undeploy"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409));

		assertThat(orchestrator.removes).isEmpty();
	}

	@Test
	void deleteIsRejectedWhileDeployedAndSucceedsAfterUndeploy() throws Exception {
		String id = idOf(create(canonicalDraft("Protected Feed")));
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());

		// Stopping a feed is always an explicit, separate act — never a delete side effect.
		mvc.perform(delete("/api/dataflows/" + id))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail")
						.value("Dataflow 'protected-feed' is deployed — undeploy it first"));

		mvc.perform(post("/api/dataflows/" + id + "/undeploy")).andExpect(status().isNoContent());
		mvc.perform(delete("/api/dataflows/" + id)).andExpect(status().isNoContent());
		mvc.perform(get("/api/dataflows/" + id)).andExpect(status().isNotFound());
	}

	@Test
	void versionsNeverRestartAcrossUndeployAndRedeploy() throws Exception {
		String id = idOf(create(canonicalDraft("Recurring Feed")));
		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(jsonPath("$.version").value(1));
		mvc.perform(post("/api/dataflows/" + id + "/undeploy")).andExpect(status().isNoContent());

		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(2));
	}

	@Test
	void aFailedOrchestratorPushFreezesNoDeployment() throws Exception {
		String id = idOf(create(canonicalDraft("Unlucky Feed")));
		orchestrator.failNextPut = new IllegalStateException("Kestra is unreachable");

		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(status().isBadGateway())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		// The freeze rolled back with the push: no half-deployment exists, and the
		// next attempt is a clean v1.
		mvc.perform(get("/api/dataflows/" + id + "/deployments"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(post("/api/dataflows/" + id + "/deploy"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(1));
	}

	// --- the recording stand-in for live Kestra ---

	record FlowPut(String slug, String flowYaml) {
	}

	static class RecordingOrchestratorFlows implements OrchestratorFlows {

		final List<FlowPut> puts = new ArrayList<>();

		final List<String> removes = new ArrayList<>();

		RuntimeException failNextPut;

		@Override
		public void put(String slug, String flowYaml) {
			if (failNextPut != null) {
				RuntimeException failure = failNextPut;
				failNextPut = null;
				throw failure;
			}
			puts.add(new FlowPut(slug, flowYaml));
		}

		@Override
		public void remove(String slug) {
			removes.add(slug);
		}

		void reset() {
			puts.clear();
			removes.clear();
			failNextPut = null;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class OrchestratorRecording {

		@Bean
		@Primary
		RecordingOrchestratorFlows recordingOrchestratorFlows() {
			return new RecordingOrchestratorFlows();
		}
	}
}
