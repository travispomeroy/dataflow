package dev.pomeroy.dataflow.controlplane.runs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pomeroy.dataflow.controlplane.TestcontainersConfiguration;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraFlowCompiler;
import dev.pomeroy.dataflow.controlplane.dataflow.Dataflows.DataflowRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * The delivered-files capture seam (issue #26): the recorder stands where the poller's
 * upsert loop does, a stub Kestra serves the count task's captured outputs, and the
 * assertions read the run-history API — the surface the demo reads. The count task
 * itself is proven live by the m1-gates walkthrough; here the contract is the
 * recorder's: capture exactly at the crossing into SUCCEEDED, sorted by name, FAILED
 * stays empty, and a settled Run never fetches again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, RunRecorderTests.KestraStub.class })
class RunRecorderTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	RunRecorder recorder;

	@Autowired
	StubKestraClient kestra;

	ObjectMapper mapper = new ObjectMapper();

	UUID dataflowId;

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	@BeforeEach
	void freshDataflow() throws Exception {
		kestra.reset();
		String config = Files.readString(Path.of("../e2e/canonical/positions-feed.config.json"));
		String body = "{\"name\": \"Recorded Feed " + SEQUENCE.incrementAndGet()
				+ "\", \"config\": " + config + "}";
		String response = mvc
				.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		dataflowId = UUID.fromString(mapper.readTree(response).path("id").stringValue());
	}

	/** The recorder's view of the polled Dataflow — deployed, canonical NY schedule. */
	private DataflowRef ref() {
		return new DataflowRef(dataflowId, "positions-feed", true, "America/New_York");
	}

	private KestraExecution execution(String id, String state) {
		return new KestraExecution(id, "positions-feed", state,
				Instant.parse("2026-07-17T06:30:00Z"),
				state.equals("SUCCESS") || state.equals("FAILED")
						? Instant.parse("2026-07-17T06:31:00Z") : null,
				null);
	}

	private String freshExecutionId() {
		return "recorder-exec-" + SEQUENCE.incrementAndGet();
	}

	/**
	 * Business Date resolution (issue #29) mirrors the compiled flow's rule (#25): no
	 * explicit input means the run date in the Schedule's timezone — 06:30Z is still
	 * 2026-07-17 in America/New_York — and an explicit input seen on a later poll
	 * replaces the resolved default (the poller may first-record a run-now execution).
	 */
	@Test
	void businessDateResolvesToTheRunDateDefaultUntilAnExplicitInputAppears() throws Exception {
		String executionId = freshExecutionId();
		UUID runId = recorder.upsert(ref(), execution(executionId, "RUNNING")).id();

		mvc.perform(get("/api/dataflows/" + dataflowId + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.businessDate").value("2026-07-17"));

		recorder.upsert(ref(), new KestraExecution(executionId, "positions-feed", "RUNNING",
				Instant.parse("2026-07-17T06:30:00Z"), null, LocalDate.parse("2026-07-15")));

		mvc.perform(get("/api/dataflows/" + dataflowId + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.businessDate").value("2026-07-15"));
	}

	@Test
	void crossingIntoSucceededCapturesTheDeliveredFilesSortedByName() throws Exception {
		String executionId = freshExecutionId();
		kestra.vars = Map.of(KestraFlowCompiler.DELIVERED_FILES_VAR, List.of(
				Map.of("name", "positions_other_2026-07-17.csv", "records", 12),
				Map.of("name", "positions_cash_2026-07-17.csv", "records", 5)));

		UUID runId = recorder.upsert(ref(), execution(executionId, "CREATED")).id();
		recorder.upsert(ref(), execution(executionId, "SUCCESS"));

		mvc.perform(get("/api/dataflows/" + dataflowId + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.deliveredFiles.length()").value(2))
				.andExpect(jsonPath("$.deliveredFiles[0].name")
						.value("positions_cash_2026-07-17.csv"))
				.andExpect(jsonPath("$.deliveredFiles[0].records").value(5))
				.andExpect(jsonPath("$.deliveredFiles[1].name")
						.value("positions_other_2026-07-17.csv"))
				.andExpect(jsonPath("$.deliveredFiles[1].records").value(12));
		assertThat(kestra.outputFetches.get()).isEqualTo(1);
	}

	@Test
	void anExecutionDiscoveredAlreadySucceededCapturesItsDeliveredFiles() throws Exception {
		String executionId = freshExecutionId();
		kestra.vars = Map.of(KestraFlowCompiler.DELIVERED_FILES_VAR,
				List.of(Map.of("name", "positions_equity_2026-07-17.csv", "records", 25)));

		UUID runId = recorder.upsert(ref(), execution(executionId, "SUCCESS")).id();

		mvc.perform(get("/api/dataflows/" + dataflowId + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deliveredFiles.length()").value(1))
				.andExpect(jsonPath("$.deliveredFiles[0].records").value(25));
	}

	@Test
	void aFailedRunKeepsAnEmptyDeliveredFilesListAndNeverFetches() throws Exception {
		String executionId = freshExecutionId();
		kestra.vars = Map.of(KestraFlowCompiler.DELIVERED_FILES_VAR,
				List.of(Map.of("name", "should-not-appear.csv", "records", 1)));

		UUID runId = recorder.upsert(ref(), execution(executionId, "CREATED")).id();
		recorder.upsert(ref(), execution(executionId, "FAILED"));

		mvc.perform(get("/api/dataflows/" + dataflowId + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.deliveredFiles.length()").value(0));
		assertThat(kestra.outputFetches.get()).isZero();
	}

	@Test
	void aSettledRunStopsFetchingOutputs() throws Exception {
		String executionId = freshExecutionId();
		kestra.vars = Map.of(KestraFlowCompiler.DELIVERED_FILES_VAR, List.of());

		recorder.upsert(ref(), execution(executionId, "SUCCESS"));
		recorder.upsert(ref(), execution(executionId, "SUCCESS"));
		recorder.upsert(ref(), execution(executionId, "SUCCESS"));

		assertThat(kestra.outputFetches.get()).isEqualTo(1);
	}

	@Test
	void aSucceededRunWithoutCapturedOutputsRecordsNone() throws Exception {
		String executionId = freshExecutionId();
		kestra.vars = Map.of();

		UUID runId = recorder.upsert(ref(), execution(executionId, "SUCCESS")).id();

		mvc.perform(get("/api/dataflows/" + dataflowId + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.deliveredFiles.length()").value(0));
	}

	/**
	 * The capture is part of the transition: a failed read-back leaves the record
	 * un-transitioned, so the next poll retries the whole step and a SUCCEEDED Run
	 * never settles without its delivered files.
	 */
	@Test
	void aFailedOutputsFetchLeavesTheTransitionForTheNextPoll() throws Exception {
		String executionId = freshExecutionId();
		UUID runId = recorder.upsert(ref(), execution(executionId, "RUNNING")).id();
		kestra.failNextOutputFetch = new IllegalStateException("Kestra hiccup");
		kestra.vars = Map.of(KestraFlowCompiler.DELIVERED_FILES_VAR,
				List.of(Map.of("name", "positions_cash_2026-07-17.csv", "records", 5)));

		assertThatIllegalStateException()
				.isThrownBy(() -> recorder.upsert(ref(), execution(executionId, "SUCCESS")));
		mvc.perform(get("/api/dataflows/" + dataflowId + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.deliveredFiles.length()").value(0));

		recorder.upsert(ref(), execution(executionId, "SUCCESS"));

		mvc.perform(get("/api/dataflows/" + dataflowId + "/runs/" + runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.deliveredFiles.length()").value(1));
	}

	// --- the scriptable stand-in for live Kestra ---

	@TestConfiguration(proxyBeanMethods = false)
	static class KestraStub {

		@Bean
		@Primary
		StubKestraClient stubKestraClient() {
			return new StubKestraClient();
		}
	}

	static class StubKestraClient implements KestraClient {

		Map<String, Object> vars = Map.of();

		RuntimeException failNextOutputFetch;

		final AtomicInteger outputFetches = new AtomicInteger();

		@Override
		public void putFlow(String flowId, String flowYaml) {
		}

		@Override
		public void deleteFlow(String flowId) {
		}

		@Override
		public KestraExecution createExecution(String flowId, Map<String, String> inputs) {
			throw new UnsupportedOperationException("these tests never trigger runs");
		}

		@Override
		public List<KestraExecution> listExecutions() {
			return List.of();
		}

		@Override
		public Map<String, Object> taskOutputVars(String executionId, String taskId) {
			if (failNextOutputFetch != null) {
				RuntimeException failure = failNextOutputFetch;
				failNextOutputFetch = null;
				throw failure;
			}
			outputFetches.incrementAndGet();
			return taskId.equals(KestraFlowCompiler.COUNT_TASK_ID) ? vars : Map.of();
		}

		void reset() {
			vars = Map.of();
			failNextOutputFetch = null;
			outputFetches.set(0);
		}
	}
}
