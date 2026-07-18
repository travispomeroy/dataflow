package dev.pomeroy.dataflow.controlplane.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pomeroy.dataflow.controlplane.TestcontainersConfiguration;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The run-now and run-history seam (issue #17). The recording fake below stands where
 * live Kestra does: run-now must write its QUEUED Run record eagerly — the API returns
 * a run ID synchronously, the poller only ever updates it later — and always executes
 * the active Deployment, never the Draft, which is why an undeployed Dataflow is
 * rejected outright. The poller loop itself is deliberately not tested here: the M1
 * gate walkthrough drives it against live Kestra (spec #10).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, RunApiTests.KestraRecording.class })
class RunApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	RecordingKestraClient kestra;

	ObjectMapper mapper = new ObjectMapper();

	@BeforeEach
	void resetKestra() {
		kestra.reset();
	}

	String createDeployed(String name) throws Exception {
		String id = create(name);
		mvc.perform(post("/api/dataflows/" + id + "/deploy")).andExpect(status().isCreated());
		return id;
	}

	String create(String name) throws Exception {
		String config = Files.readString(Path.of("../e2e/canonical/positions-feed.config.json"));
		String body = "{\"name\": " + mapper.writeValueAsString(name) + ", \"config\": " + config + "}";
		String response = mvc
				.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return mapper.readTree(response).path("id").stringValue();
	}

	// --- run-now ---

	@Test
	void runNowOnADeployedDataflowReturnsAQueuedRunImmediately() throws Exception {
		String id = createDeployed("Runnable Feed");

		String response = mvc.perform(post("/api/dataflows/" + id + "/run-now"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				// The raw Kestra state travels verbatim in the detail field.
				.andExpect(jsonPath("$.detail").value("CREATED"))
				.andExpect(jsonPath("$.startedAt").isNotEmpty())
				.andExpect(jsonPath("$.endedAt").doesNotExist())
				// Delivered-files fields exist but stay empty until M2.
				.andExpect(jsonPath("$.deliveredFiles").isArray())
				.andExpect(jsonPath("$.deliveredFiles", hasSize(0)))
				.andReturn().getResponse().getContentAsString();
		String runId = mapper.readTree(response).path("id").stringValue();
		assertThat(mapper.readTree(response).path("kestraExecutionId").stringValue())
				.isEqualTo(kestra.lastExecutionId);

		// The record is already readable at the Location the response points to.
		mvc.perform(get("/api/dataflows/" + id + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.kestraExecutionId").value(kestra.lastExecutionId));

		// And the execution really was created against the flow — whose definition is
		// the active Deployment's frozen plan, never the Draft.
		assertThat(kestra.executionCreates).containsExactly("runnable-feed");
	}

	@Test
	void runNowPointsAtTheRunRecordItCreated() throws Exception {
		String id = createDeployed("Located Feed");

		String response = mvc.perform(post("/api/dataflows/" + id + "/run-now"))
				.andExpect(status().isAccepted())
				.andExpect(header().exists("Location"))
				.andReturn().getResponse().getContentAsString();
		String runId = mapper.readTree(response).path("id").stringValue();

		mvc.perform(post("/api/dataflows/" + id + "/run-now"))
				.andExpect(header().string("Location",
						not("/api/dataflows/" + id + "/runs/" + runId)));
	}

	@Test
	void runNowOnAnUndeployedDataflowIsRejected() throws Exception {
		String id = create("Never Deployed");

		mvc.perform(post("/api/dataflows/" + id + "/run-now"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409));

		assertThat(kestra.executionCreates).isEmpty();
	}

	@Test
	void runNowAfterUndeployIsRejectedAgain() throws Exception {
		String id = createDeployed("Retired Feed");
		mvc.perform(post("/api/dataflows/" + id + "/undeploy")).andExpect(status().isNoContent());

		mvc.perform(post("/api/dataflows/" + id + "/run-now"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void runNowOnAnUnknownDataflowIsNotFound() throws Exception {
		mvc.perform(post("/api/dataflows/" + UUID.randomUUID() + "/run-now"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void aFailedExecutionCreateIsABadGatewayAndRecordsNoRun() throws Exception {
		String id = createDeployed("Unlucky Feed");
		kestra.failNextCreate = new IllegalStateException("Kestra is unreachable");

		mvc.perform(post("/api/dataflows/" + id + "/run-now"))
				.andExpect(status().isBadGateway())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		mvc.perform(get("/api/dataflows/" + id + "/runs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	// --- run history ---

	@Test
	void runHistoryListsRunsNewestFirst() throws Exception {
		String id = createDeployed("Storied Feed");
		mvc.perform(post("/api/dataflows/" + id + "/run-now")).andExpect(status().isAccepted());
		String earlier = kestra.lastExecutionId;
		mvc.perform(post("/api/dataflows/" + id + "/run-now")).andExpect(status().isAccepted());
		String later = kestra.lastExecutionId;

		mvc.perform(get("/api/dataflows/" + id + "/runs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].kestraExecutionId").value(later))
				.andExpect(jsonPath("$[1].kestraExecutionId").value(earlier))
				.andExpect(jsonPath("$[0].status").value("QUEUED"))
				.andExpect(jsonPath("$[0].startedAt").isNotEmpty());
	}

	@Test
	void runHistoryIsPerDataflow() throws Exception {
		String busy = createDeployed("Busy Feed");
		String quiet = createDeployed("Quiet Feed");
		mvc.perform(post("/api/dataflows/" + busy + "/run-now")).andExpect(status().isAccepted());

		mvc.perform(get("/api/dataflows/" + quiet + "/runs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void runHistoryOnAnUnknownDataflowIsNotFound() throws Exception {
		mvc.perform(get("/api/dataflows/" + UUID.randomUUID() + "/runs"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void anUnknownRunIdIsNotFound() throws Exception {
		String id = createDeployed("Lonely Feed");

		mvc.perform(get("/api/dataflows/" + id + "/runs/" + UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	// --- the recording stand-in for live Kestra ---

	@TestConfiguration(proxyBeanMethods = false)
	static class KestraRecording {

		@Bean
		@Primary
		RecordingKestraClient recordingKestraClient() {
			return new RecordingKestraClient();
		}
	}

	static class RecordingKestraClient implements KestraClient {

		final List<String> executionCreates = new ArrayList<>();

		RuntimeException failNextCreate;

		String lastExecutionId;

		// Kestra execution IDs are globally unique; the run upsert is keyed by them,
		// so the fake must never mint the same ID twice across tests.
		private static final AtomicInteger SEQUENCE = new AtomicInteger();

		@Override
		public void putFlow(String flowId, String flowYaml) {
			// Deploys in these tests need no live Kestra either.
		}

		@Override
		public void deleteFlow(String flowId) {
		}

		@Override
		public KestraExecution createExecution(String flowId) {
			if (failNextCreate != null) {
				RuntimeException failure = failNextCreate;
				failNextCreate = null;
				throw failure;
			}
			executionCreates.add(flowId);
			int sequence = SEQUENCE.incrementAndGet();
			lastExecutionId = "exec-" + sequence;
			return new KestraExecution(lastExecutionId, flowId, "CREATED",
					Instant.parse("2026-07-18T06:30:00Z").plusSeconds(sequence), null);
		}

		@Override
		public List<KestraExecution> listExecutions() {
			return List.of();
		}

		void reset() {
			executionCreates.clear();
			failNextCreate = null;
			lastExecutionId = null;
		}
	}
}
