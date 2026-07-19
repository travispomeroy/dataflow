package dev.pomeroy.dataflow.controlplane.compilerkestra.internal;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraExecution;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraFlowCompiler;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Kestra's flows API over HTTP: basic auth (Kestra 1.x always authenticates), the
 * {@code main} tenant in every path, YAML request bodies. Create-vs-overwrite is
 * resolved optimistically — update first, fall back to create on 404 — because after
 * the first deploy every push is an overwrite.
 *
 * <p>The client is pinned to HTTP/1.1: the JDK client's default cleartext HTTP/2
 * upgrade ({@code Upgrade: h2c}) is a request Kestra's Netty server never answers, so
 * without the pin every write hangs forever. Timeouts are set for the same reason a
 * push runs inside the deploy transaction — a wedged Kestra must become a rolled-back
 * 502, not a stuck deploy thread.
 */
@Component
class RestKestraClient implements KestraClient {

	private static final MediaType APPLICATION_YAML = MediaType.parseMediaType("application/x-yaml");

	private static final int EXECUTION_PAGE_SIZE = 100;

	private final RestClient client;

	RestKestraClient(@Value("${kestra.api.url}") String url,
			@Value("${kestra.api.username}") String username,
			@Value("${kestra.api.password}") String password) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder()
						.version(HttpClient.Version.HTTP_1_1)
						.connectTimeout(Duration.ofSeconds(5))
						.build());
		requestFactory.setReadTimeout(Duration.ofSeconds(30));
		this.client = RestClient.builder().baseUrl(url)
				.requestFactory(requestFactory)
				.requestInterceptor((request, body, execution) -> {
					request.getHeaders().setBasicAuth(username, password);
					return execution.execute(request, body);
				})
				.build();
	}

	@Override
	public void putFlow(String flowId, String flowYaml) {
		try {
			client.put()
					.uri("/api/v1/main/flows/{namespace}/{id}", KestraFlowCompiler.NAMESPACE, flowId)
					.contentType(APPLICATION_YAML).body(flowYaml)
					.retrieve().toBodilessEntity();
		}
		catch (HttpClientErrorException.NotFound e) {
			client.post().uri("/api/v1/main/flows")
					.contentType(APPLICATION_YAML).body(flowYaml)
					.retrieve().toBodilessEntity();
		}
	}

	@Override
	public void deleteFlow(String flowId) {
		try {
			client.delete()
					.uri("/api/v1/main/flows/{namespace}/{id}", KestraFlowCompiler.NAMESPACE, flowId)
					.retrieve().toBodilessEntity();
		}
		catch (HttpClientErrorException.NotFound e) {
			// Already absent — the goal state holds.
		}
	}

	@Override
	public KestraExecution createExecution(String flowId, Map<String, String> inputs) {
		// Execution inputs travel as multipart form fields; with none to send, the
		// POST stays bodiless — Kestra accepts the trigger without a form and the
		// flow's own input defaults resolve.
		RestClient.RequestBodySpec request = client.post()
				.uri("/api/v1/main/executions/{namespace}/{id}", KestraFlowCompiler.NAMESPACE, flowId);
		if (!inputs.isEmpty()) {
			MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
			inputs.forEach(form::add);
			request.contentType(MediaType.MULTIPART_FORM_DATA).body(form);
		}
		return request.retrieve().body(ExecutionDocument.class).toExecution();
	}

	@Override
	public List<KestraExecution> listExecutions() {
		List<KestraExecution> executions = new ArrayList<>();
		for (int page = 1;; page++) {
			ExecutionPage result = client.get()
					.uri("/api/v1/main/executions/search?namespace={namespace}&page={page}&size={size}",
							KestraFlowCompiler.NAMESPACE, page, EXECUTION_PAGE_SIZE)
					.retrieve().body(ExecutionPage.class);
			result.results().forEach(document -> executions.add(document.toExecution()));
			if (executions.size() >= result.total() || result.results().isEmpty()) {
				return executions;
			}
		}
	}

	@Override
	public Map<String, Object> taskOutputVars(String executionId, String taskId) {
		ExecutionDetail detail;
		try {
			detail = client.get()
					.uri("/api/v1/main/executions/{executionId}", executionId)
					.retrieve().body(ExecutionDetail.class);
		}
		catch (HttpClientErrorException.NotFound e) {
			// An execution Kestra no longer knows captured nothing knowable.
			return Map.of();
		}
		return detail.capturedVars(taskId);
	}

	/** The slice of Kestra's execution document the control plane reads. */
	record ExecutionDocument(String id, String flowId, ExecutionState state) {

		KestraExecution toExecution() {
			return new KestraExecution(id, flowId, state.current(), state.startDate(),
					state.endDate());
		}
	}

	record ExecutionState(String current, Instant startDate, Instant endDate) {
	}

	record ExecutionPage(long total, List<ExecutionDocument> results) {
	}

	/**
	 * The task-run slice of a full execution document: script-task captured outputs
	 * nest under {@code outputs.vars}. A retried task appears once per run — the last
	 * run's capture is the one that counts, matching Kestra's own outputs view.
	 */
	record ExecutionDetail(List<TaskRunDocument> taskRunList) {

		Map<String, Object> capturedVars(String taskId) {
			if (taskRunList == null) {
				return Map.of();
			}
			return taskRunList.stream()
					.filter(taskRun -> taskId.equals(taskRun.taskId()))
					.reduce((first, last) -> last)
					.map(TaskRunDocument::capturedVars)
					.orElse(Map.of());
		}
	}

	record TaskRunDocument(String taskId, Map<String, Object> outputs) {

		@SuppressWarnings("unchecked")
		Map<String, Object> capturedVars() {
			return outputs != null && outputs.get("vars") instanceof Map<?, ?> vars
					? (Map<String, Object>) vars
					: Map.of();
		}
	}
}
