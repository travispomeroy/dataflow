package dev.pomeroy.dataflow.controlplane.runner.internal;

import dev.pomeroy.dataflow.controlplane.compilernifi.NiFiArtifact;
import dev.pomeroy.dataflow.controlplane.runner.NiFiDeployments;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * NiFi's REST API for the deploy-time replace dance, exactly as spike #38 proved it:
 * token auth ({@code POST /access/token}, the bare JWT back), then — for an existing
 * process group named by the slug — stop → drop all queues (async request, polled) →
 * disable controller services (polled settled) → delete with a fresh revision; delete
 * the old parameter context (NiFi does not protect the binding, and deleting the
 * group does not cascade); upload the flow definition in one multipart call; record
 * the Deployment version in the group's comments; create and bind the parameter
 * context. Controller services arrive DISABLED (spike #38) and deploy leaves them
 * that way — the credentials service refuses to enable against the fresh context's
 * empty sensitive values, so enabling is the run driver's job once real values are
 * bound (the "or the run protocol before start" half of the spike's obligation).
 *
 * <p>TLS certificate verification is disabled against NiFi's boot-generated
 * self-signed cert — the M0 posture ({@code curl -sk} in the compose healthcheck).
 * That means the six-way no-op {@link X509ExtendedTrustManager}: the JDK wraps a
 * plain no-op {@code X509TrustManager} in its own endpoint-identity check, which the
 * random container-hostname cert then fails.
 *
 * <p>Failures surface as runtime exceptions for the deploy lifecycle to translate
 * into a rolled-back 502 — this module takes no view on HTTP error shapes beyond
 * NiFi's own.
 */
@Component
class RestNiFiDeployments implements NiFiDeployments {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final Duration POLL_BUDGET = Duration.ofSeconds(30);

	private final RestClient client;

	private final String username;

	private final String password;

	RestNiFiDeployments(@Value("${nifi.api.url}") String url,
			@Value("${nifi.api.username}") String username,
			@Value("${nifi.api.password}") String password) {
		this.username = username;
		this.password = password;
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder()
						.version(HttpClient.Version.HTTP_1_1)
						.sslContext(trustingSslContext())
						.connectTimeout(Duration.ofSeconds(5))
						.build());
		requestFactory.setReadTimeout(Duration.ofSeconds(30));
		this.client = RestClient.builder().baseUrl(url).requestFactory(requestFactory)
				.build();
	}

	@Override
	public void put(String slug, int deploymentVersion, String flowDefinitionJson) {
		String token = token();
		String rootId = json(get(token, "/flow/process-groups/root"))
				.path("processGroupFlow").path("id").asString();
		findProcessGroup(token, slug)
				.ifPresent(existing -> deleteProcessGroup(token, existing));
		findParameterContext(token, slug)
				.ifPresent(existing -> deleteParameterContext(token, existing));
		String groupId = upload(token, rootId, slug, flowDefinitionJson);
		recordVersionInComments(token, groupId, deploymentVersion);
		bind(token, groupId, createParameterContext(token, slug));
		// Controller services stay DISABLED as uploaded: the credentials service
		// refuses to enable against the context's empty sensitive values (found
		// live in M4.4), so the run driver enables services after late-binding —
		// the "or the run protocol before start" half of spike #38's obligation.
	}

	private String token() {
		return client.post().uri("/access/token")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body("username=%s&password=%s".formatted(urlEncode(username),
						urlEncode(password)))
				.retrieve().body(String.class);
	}

	/** The replace half of deploy: spike #38 §3's verified ordering, then delete. */
	private void deleteProcessGroup(String token, String groupId) {
		put(token, "/flow/process-groups/" + groupId,
				Map.of("id", groupId, "state", "STOPPED"));
		dropAllQueues(token, groupId);
		controllerServicesState(token, groupId, "DISABLED");
		awaitControllerServicesState(token, groupId, "DISABLED");
		long revision = revisionOf(token, "/process-groups/" + groupId);
		client.delete()
				.uri("/process-groups/{id}?version={revision}", groupId, revision)
				.header("Authorization", "Bearer " + token).retrieve().toBodilessEntity();
	}

	/** Async request-object dance: create, poll finished, delete the request. */
	private void dropAllQueues(String token, String groupId) {
		String base = "/process-groups/" + groupId + "/empty-all-connections-requests";
		String requestId = json(post(token, base, null)).path("dropRequest").path("id")
				.asString();
		poll("queue drop", () -> json(get(token, base + "/" + requestId))
				.path("dropRequest").path("finished").asBoolean());
		delete(token, base + "/" + requestId);
	}

	private void controllerServicesState(String token, String groupId, String state) {
		put(token, "/flow/process-groups/" + groupId + "/controller-services",
				Map.of("id", groupId, "state", state));
	}

	/** Disabling settles asynchronously (~2s live); delete refuses until it has. */
	private void awaitControllerServicesState(String token, String groupId, String state) {
		poll("controller services " + state, () -> {
			JsonNode services = json(
					get(token, "/flow/process-groups/" + groupId + "/controller-services"))
					.path("controllerServices");
			for (JsonNode service : services) {
				if (!state.equals(service.path("component").path("state").asString())) {
					return false;
				}
			}
			return true;
		});
	}

	private void deleteParameterContext(String token, String contextId) {
		long revision = revisionOf(token, "/parameter-contexts/" + contextId);
		client.delete()
				.uri("/parameter-contexts/{id}?version={revision}", contextId, revision)
				.header("Authorization", "Bearer " + token).retrieve().toBodilessEntity();
	}

	/** One multipart call — the artifact is a single self-contained document. */
	private String upload(String token, String rootId, String slug,
			String flowDefinitionJson) {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("groupName", slug);
		form.add("positionX", "0");
		form.add("positionY", "0");
		form.add("clientId", "control-plane");
		form.add("disconnectedNodeAcknowledged", "false");
		form.add("file", new ByteArrayResource(
				flowDefinitionJson.getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return slug + ".flow-definition.json";
			}
		});
		String response = client.post()
				.uri("/process-groups/{rootId}/process-groups/upload", rootId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.MULTIPART_FORM_DATA).body(form)
				.retrieve().body(String.class);
		return json(response).path("id").asString();
	}

	/** Name = slug is the identity; the version is auditable where operators look. */
	private void recordVersionInComments(String token, String groupId, int version) {
		long revision = revisionOf(token, "/process-groups/" + groupId);
		put(token, "/process-groups/" + groupId,
				Map.of("revision", Map.of("version", revision),
						"component", Map.of("id", groupId, "comments",
								"Deployment version " + version)));
	}

	/**
	 * The artifact's parameter contract with empty values: the flow definition
	 * carries names only (ADR-0002; NiFi drops sensitive values from definitions
	 * anyway), and every run's driver late-binds real values before starting.
	 */
	private String createParameterContext(String token, String slug) {
		List<Map<String, Object>> parameters = NiFiArtifact.PARAMETERS.stream()
				.map(parameter -> Map.<String, Object>of("parameter",
						Map.of("name", parameter.name(), "sensitive",
								parameter.sensitive(), "value", "")))
				.toList();
		String response = post(token, "/parameter-contexts",
				Map.of("revision", Map.of("version", 0),
						"component", Map.of("name", slug, "parameters", parameters)));
		return json(response).path("id").asString();
	}

	private void bind(String token, String groupId, String contextId) {
		long revision = revisionOf(token, "/process-groups/" + groupId);
		put(token, "/process-groups/" + groupId,
				Map.of("revision", Map.of("version", revision),
						"component", Map.of("id", groupId,
								"parameterContext", Map.of("id", contextId))));
	}

	private Optional<String> findProcessGroup(String token, String name) {
		return findByName(json(get(token, "/flow/process-groups/root"))
				.path("processGroupFlow").path("flow").path("processGroups"), name);
	}

	private Optional<String> findParameterContext(String token, String name) {
		return findByName(json(get(token, "/flow/parameter-contexts"))
				.path("parameterContexts"), name);
	}

	private Optional<String> findByName(JsonNode entities, String name) {
		for (JsonNode entity : entities) {
			if (name.equals(entity.path("component").path("name").asString())) {
				return Optional.of(entity.path("id").asString());
			}
		}
		return Optional.empty();
	}

	private long revisionOf(String token, String uri) {
		return json(get(token, uri)).path("revision").path("version").asLong();
	}

	private void poll(String what, BooleanSupplier done) {
		Instant deadline = Instant.now().plus(POLL_BUDGET);
		while (!done.getAsBoolean()) {
			if (Instant.now().isAfter(deadline)) {
				throw new IllegalStateException(
						what + " did not settle within " + POLL_BUDGET.toSeconds() + "s");
			}
			try {
				Thread.sleep(500);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(what + " interrupted", e);
			}
		}
	}

	private String get(String token, String uri) {
		return client.get().uri(uri).header("Authorization", "Bearer " + token)
				.retrieve().body(String.class);
	}

	private String post(String token, String uri, Map<String, Object> body) {
		RestClient.RequestBodySpec spec = client.post().uri(uri)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON);
		if (body != null) {
			spec.body(MAPPER.writeValueAsString(body));
		}
		return spec.retrieve().body(String.class);
	}

	private void put(String token, String uri, Map<String, Object> body) {
		client.put().uri(uri).header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.body(MAPPER.writeValueAsString(body))
				.retrieve().toBodilessEntity();
	}

	private void delete(String token, String uri) {
		client.delete().uri(uri).header("Authorization", "Bearer " + token)
				.retrieve().toBodilessEntity();
	}

	private JsonNode json(String body) {
		return MAPPER.readTree(body);
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/**
	 * Accept any certificate, including its hostname — must extend
	 * {@link X509ExtendedTrustManager} so the JDK performs no identity check of its
	 * own on top.
	 */
	private static SSLContext trustingSslContext() {
		X509ExtendedTrustManager trustAll = new X509ExtendedTrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {
			}

			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType,
					Socket socket) {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType,
					Socket socket) {
			}

			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType,
					SSLEngine engine) {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType,
					SSLEngine engine) {
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new TrustManager[] { trustAll }, new SecureRandom());
			return context;
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("cannot build the trust-all SSL context", e);
		}
	}
}
