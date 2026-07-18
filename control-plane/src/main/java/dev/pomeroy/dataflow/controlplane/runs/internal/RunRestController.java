package dev.pomeroy.dataflow.controlplane.runs.internal;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import dev.pomeroy.dataflow.controlplane.dataflow.Dataflows;
import dev.pomeroy.dataflow.controlplane.dataflow.Dataflows.DataflowRef;
import dev.pomeroy.dataflow.controlplane.runs.RunStatus;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Run-now and run history (issue #17). Run-now executes the active Deployment's
 * frozen plan, never the Draft — by construction: the Kestra flow it triggers is
 * whatever the last deploy pushed, so an undeployed Dataflow has nothing to run and
 * is rejected. The QUEUED record is written eagerly so the response carries a run ID
 * synchronously; from there the poller owns the record.
 */
@RestController
@RequestMapping("/api/dataflows/{dataflowId}")
class RunRestController {

	private final Dataflows dataflows;

	private final KestraClient kestra;

	private final RunRecorder recorder;

	private final RunRepository runs;

	private final ObjectMapper mapper;

	RunRestController(Dataflows dataflows, KestraClient kestra, RunRecorder recorder,
			RunRepository runs, ObjectMapper mapper) {
		this.dataflows = dataflows;
		this.kestra = kestra;
		this.recorder = recorder;
		this.runs = runs;
		this.mapper = mapper;
	}

	@PostMapping("/run-now")
	ResponseEntity<RunResponse> runNow(@PathVariable UUID dataflowId) {
		DataflowRef dataflow = find(dataflowId);
		if (!dataflow.deployed()) {
			throw problem(HttpStatus.CONFLICT,
					"Dataflow '%s' is not deployed — run-now executes the active Deployment"
							.formatted(dataflow.slug()));
		}
		RunEntity run = recorder.upsert(dataflow.id(), execute(dataflow.slug()));
		return ResponseEntity.accepted()
				.location(URI.create("/api/dataflows/" + dataflowId + "/runs/" + run.id()))
				.body(response(run));
	}

	@GetMapping("/runs")
	List<RunResponse> history(@PathVariable UUID dataflowId) {
		return runs.findByDataflowIdOrderByStartedAtDesc(find(dataflowId).id()).stream()
				.map(this::response).toList();
	}

	@GetMapping("/runs/{runId}")
	RunResponse run(@PathVariable UUID dataflowId, @PathVariable UUID runId) {
		return runs.findByIdAndDataflowId(runId, find(dataflowId).id())
				.map(this::response)
				.orElseThrow(() -> problem(HttpStatus.NOT_FOUND,
						"No Run '%s' for Dataflow '%s'".formatted(runId, dataflowId)));
	}

	/** A wedged Kestra becomes a 502 problem detail, same as a failed deploy push. */
	private KestraExecution execute(String slug) {
		try {
			return kestra.createExecution(slug);
		}
		catch (RuntimeException e) {
			throw problem(HttpStatus.BAD_GATEWAY,
					"The Orchestrator did not accept run-now: " + e.getMessage());
		}
	}

	private DataflowRef find(UUID id) {
		return dataflows.find(id).orElseThrow(
				() -> problem(HttpStatus.NOT_FOUND, "No Dataflow with id '%s'".formatted(id)));
	}

	private static ErrorResponseException problem(HttpStatus status, String detail) {
		return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail),
				null);
	}

	private RunResponse response(RunEntity run) {
		return new RunResponse(run.id(), run.status(), run.detail(), run.kestraExecutionId(),
				run.startedAt(), run.endedAt(), mapper.readTree(run.deliveredFiles()));
	}

	record RunResponse(UUID id, RunStatus status, String detail, String kestraExecutionId,
			Instant startedAt, Instant endedAt, JsonNode deliveredFiles) {
	}
}
