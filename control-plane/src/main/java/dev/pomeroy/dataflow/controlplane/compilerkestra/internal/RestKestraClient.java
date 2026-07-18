package dev.pomeroy.dataflow.controlplane.compilerkestra.internal;

import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraClient;
import dev.pomeroy.dataflow.controlplane.compilerkestra.KestraFlowCompiler;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
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
}
