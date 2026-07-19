package dev.pomeroy.dataflow.controlplane.compilernifi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pomeroy.dataflow.controlplane.compiler.ApiExtraction;
import dev.pomeroy.dataflow.controlplane.compiler.Engine;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlanJson;
import dev.pomeroy.dataflow.controlplane.compiler.Extraction;
import dev.pomeroy.dataflow.controlplane.compiler.Pagination;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationStyle;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationTermination;
import dev.pomeroy.dataflow.controlplane.compilernifi.internal.DeterministicFlowDefinitionCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The NiFi compiler's golden seam (issue #40, M1/M2 golden-test prior art): the
 * canonical Positions Feed Execution Plan — read from its own committed golden, the
 * module's only input, flipped to {@code engine: nifi} — compiles to a flow
 * definition that byte-matches the committed golden, and the cyclic pagination-loop
 * subgraph is pinned by its own focused golden. Regenerate with {@code ./mvnw test
 * -Dtest=NiFiArtifactGoldenTests -Dregenerate.goldens} after a reviewed
 * artifact-shape change — regeneration is deterministic, so a rerun must produce
 * byte-identical files. Surefire runs with the module base directory as working
 * directory, so the repo root is one level up.
 */
class NiFiArtifactGoldenTests {

	static final Path GOLDEN_PLAN = Path.of("../e2e/golden/positions-feed.plan.json");

	static final Path GOLDEN_FLOW_DEFINITION = Path
			.of("../e2e/golden/positions-feed.flow-definition.json");

	static final Path GOLDEN_PAGINATION_LOOP = Path
			.of("../e2e/golden/positions-feed.pagination-loop.json");

	static final ObjectMapper MAPPER = JsonMapper.builder().build();

	NiFiFlowCompiler compiler = new DeterministicFlowDefinitionCompiler();

	@Test
	void theCanonicalPlanCompilesToTheCommittedGoldenByteForByte() throws Exception {
		NiFiArtifact artifact = compiler.compile(canonicalPlan());

		if (System.getProperty("regenerate.goldens") != null) {
			Files.createDirectories(GOLDEN_FLOW_DEFINITION.getParent());
			Files.writeString(GOLDEN_FLOW_DEFINITION, artifact.flowDefinitionJson());
		}

		assertThat(artifact.fileName()).isEqualTo("positions-feed.flow-definition.json");
		assertThat(artifact.flowDefinitionJson())
				.isEqualTo(Files.readString(GOLDEN_FLOW_DEFINITION));
	}

	/**
	 * The focused golden the ticket demands: the one deliberately cyclic subgraph —
	 * the positions page loop's processors and every connection among them, including
	 * the loop-back — extracted from the compiled document and pinned on its own, so
	 * a loop-shape regression is visible without diffing the whole artifact.
	 */
	@Test
	void theCyclicPaginationLoopSubgraphMatchesItsOwnGolden() throws Exception {
		Map<String, Object> document = read(
				compiler.compile(canonicalPlan()).flowDefinitionJson());
		Map<String, Object> contents = section(document, "flowContents");
		List<Map<String, Object>> processors = components(contents, "processors").stream()
				.filter(processor -> ((String) processor.get("name"))
						.startsWith("positions: "))
				.toList();
		Set<String> loopIds = processors.stream()
				.map(processor -> (String) processor.get("identifier"))
				.collect(Collectors.toSet());
		List<Map<String, Object>> connections = components(contents, "connections")
				.stream()
				.filter(connection -> loopIds
						.contains(section(connection, "source").get("id"))
						&& loopIds.contains(section(connection, "destination").get("id")))
				.toList();
		Map<String, Object> subgraph = new LinkedHashMap<>();
		subgraph.put("processors", processors);
		subgraph.put("connections", connections);
		String json = prettyPrinted(subgraph);

		if (System.getProperty("regenerate.goldens") != null) {
			Files.createDirectories(GOLDEN_PAGINATION_LOOP.getParent());
			Files.writeString(GOLDEN_PAGINATION_LOOP, json);
		}

		assertThat(json).isEqualTo(Files.readString(GOLDEN_PAGINATION_LOOP));
	}

	@Test
	void compilingTwiceProducesByteIdenticalOutput() throws Exception {
		NiFiArtifact first = compiler.compile(canonicalPlan());
		NiFiArtifact second = compiler.compile(canonicalPlan());

		assertThat(second).isEqualTo(first);
	}

	/**
	 * The spec's extraction contract: every API gets its own generated sequential
	 * cursor loop with the plan's page size, and the graph is genuinely cyclic — the
	 * next-cursor increment connects back to the fetch.
	 */
	@Test
	void everyApiGetsItsOwnSequentialCursorLoopWithThePlansPageSize() throws Exception {
		String json = compiler.compile(canonicalPlan()).flowDefinitionJson();
		Map<String, Object> contents = section(read(json), "flowContents");

		assertThat(json)
				.contains("http://wiremock:8080/positions?page=${page}&pageSize=50")
				.contains("http://wiremock:8080/investors?page=${page}&pageSize=4")
				.contains("http://wiremock:8080/orders?page=${page}&pageSize=75");
		for (String api : List.of("positions", "investors", "orders")) {
			assertThat(components(contents, "connections").stream()
					.filter(connection -> section(connection, "source").get("name")
							.equals(api + ": next cursor")
							&& section(connection, "destination").get("name")
									.equals(api + ": fetch page")))
					.as("the %s loop-back connection", api).hasSize(1);
		}
	}

	/** ADR-0006: the orders merge is a latest-per-key reduction, before its merge. */
	@Test
	void theCollapsingApiReducesToTheLatestRecordBeforeItsMerge() throws Exception {
		String json = compiler.compile(canonicalPlan()).flowDefinitionJson();

		assertThat(json).contains("orders: keep the latest per key");
		assertThat(json.indexOf("orders: keep the latest per key"))
				.isLessThan(json.indexOf("merge orders"));
	}

	/**
	 * Verbatim numerics (the M2 lesson): every reader and writer carries an explicit
	 * all-string schema — no schema inference anywhere, no field ever typed
	 * numerically.
	 */
	@Test
	void everyRecordSchemaIsExplicitAndAllString() throws Exception {
		Map<String, Object> contents = section(
				read(compiler.compile(canonicalPlan()).flowDefinitionJson()),
				"flowContents");
		List<Map<String, Object>> services = components(contents, "controllerServices");

		for (Map<String, Object> service : services) {
			String type = (String) service.get("type");
			if (!type.endsWith("JsonTreeReader") && !type.endsWith("RecordSetWriter")) {
				continue;
			}
			Map<String, Object> properties = section(service, "properties");
			assertThat(properties.get("Schema Access Strategy"))
					.as("schema strategy of %s", service.get("name"))
					.isEqualTo("schema-text-property");
			Map<String, Object> schema = read((String) properties.get("Schema Text"));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> fields = (List<Map<String, Object>>) schema
					.get("fields");
			assertThat(fields).as("fields of %s", service.get("name")).isNotEmpty();
			for (Map<String, Object> field : fields) {
				assertThat(field.get("type"))
						.as("type of %s.%s", service.get("name"), field.get("name"))
						.isIn("string", List.of("null", "string"));
			}
		}
	}

	/**
	 * Every failure relationship routes to the one dedicated failure funnel — never
	 * auto-terminated — and the artifact exposes exactly those connection ids for the
	 * run driver's poll (spike #38).
	 */
	@Test
	void everyFailureRelationshipRoutesToTheDedicatedFunnelAndIsExposed()
			throws Exception {
		NiFiArtifact artifact = compiler.compile(canonicalPlan());
		Map<String, Object> contents = section(read(artifact.flowDefinitionJson()),
				"flowContents");

		List<String> intoFunnel = new ArrayList<>();
		for (Map<String, Object> connection : components(contents, "connections")) {
			@SuppressWarnings("unchecked")
			List<String> relationships = (List<String>) connection
					.get("selectedRelationships");
			boolean failureish = relationships.stream().anyMatch(relationship -> Set
					.of("failure", "Failure", "Retry", "No Retry", "timeout", "unmatched")
					.contains(relationship));
			boolean toFunnel = section(connection, "destination").get("type")
					.equals("FUNNEL");
			if (toFunnel) {
				intoFunnel.add((String) connection.get("identifier"));
				continue;
			}
			assertThat(failureish)
					.as("connection '%s' carries %s but does not target the funnel",
							connection.get("name"), relationships)
					.isFalse();
		}
		for (Map<String, Object> processor : components(contents, "processors")) {
			@SuppressWarnings("unchecked")
			List<String> autoTerminated = (List<String>) processor
					.get("autoTerminatedRelationships");
			assertThat(autoTerminated)
					.as("auto-terminated relationships of %s", processor.get("name"))
					.doesNotContain("failure", "Failure", "timeout");
		}
		assertThat(artifact.failureConnectionIds()).isEqualTo(intoFunnel);
	}

	/**
	 * The RUN_ONCE protocol's compiler half (spike #38): every seed is minted
	 * DISABLED so a process-group start skips it, and the artifact names the seeds
	 * for the driver to enable and trigger.
	 */
	@Test
	void seedsAreMintedDisabledAndNamedByTheArtifact() throws Exception {
		NiFiArtifact artifact = compiler.compile(canonicalPlan());
		Map<String, Object> contents = section(read(artifact.flowDefinitionJson()),
				"flowContents");

		List<String> seedIds = new ArrayList<>();
		for (Map<String, Object> processor : components(contents, "processors")) {
			if (((String) processor.get("type")).endsWith("GenerateFlowFile")) {
				assertThat(processor.get("scheduledState"))
						.as("scheduled state of %s", processor.get("name"))
						.isEqualTo("DISABLED");
				seedIds.add((String) processor.get("identifier"));
			}
			else {
				assertThat(processor.get("scheduledState")).isEqualTo("ENABLED");
			}
		}
		assertThat(seedIds).hasSize(3);
		assertThat(artifact.seedProcessorIds()).isEqualTo(seedIds);
	}

	/**
	 * ADR-0002: the artifact carries parameter names only — per-run and sensitive
	 * values late-bind through the parameter context. The mock world's MinIO root
	 * credentials are committed in infra/.env, so the test can grep for them
	 * literally.
	 */
	@Test
	void theArtifactCarriesParameterNamesAndNeverACredentialValue() throws Exception {
		String json = compiler.compile(canonicalPlan()).flowDefinitionJson();

		assertThat(json).contains("#{businessDate}").contains("#{runId}")
				.contains("#{minioAccessKey}").contains("#{minioSecretKey}");
		assertThat(json).doesNotContain(envValue("MINIO_ROOT_USER"))
				.doesNotContain(envValue("MINIO_ROOT_PASSWORD"));
	}

	/** Hand-written script blocks would void the ADR-0003 legibility comparison. */
	@Test
	void executeScriptNeverAppears() throws Exception {
		assertThat(compiler.compile(canonicalPlan()).flowDefinitionJson())
				.doesNotContain("ExecuteScript");
	}

	/**
	 * The sad path the ticket demands: pagination this compiler cannot generate a
	 * loop for must refuse loudly — never silently extract page 1 and call it a feed.
	 * A one-valued enum cannot express a wrong style, but an absent one (a plan
	 * predating the pagination schema, or a hand-built plan) is representable.
	 */
	@Test
	void anUnsupportedPaginationStyleRefusesToCompile() throws Exception {
		ExecutionPlan plan = withOrdersPagination(canonicalPlan(),
				new Pagination(null, 75, PaginationTermination.TOTAL_PAGES));

		assertThatThrownBy(() -> compiler.compile(plan))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining("orders").hasMessageContaining("pageNumber");
	}

	@Test
	void anUnsupportedPaginationTerminationRefusesToCompile() throws Exception {
		ExecutionPlan plan = withOrdersPagination(canonicalPlan(),
				new Pagination(PaginationStyle.PAGE_NUMBER, 75, null));

		assertThatThrownBy(() -> compiler.compile(plan))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining("orders").hasMessageContaining("totalPages");
	}

	private ExecutionPlan canonicalPlan() throws Exception {
		ExecutionPlan plan = ExecutionPlanJson.read(Files.readString(GOLDEN_PLAN));
		return new ExecutionPlan(plan.slug(), Engine.NIFI, plan.executionModel(),
				plan.schedule(), plan.extraction(), plan.transforms(), plan.files(),
				plan.staging(), plan.delivery());
	}

	private ExecutionPlan withOrdersPagination(ExecutionPlan plan, Pagination pagination) {
		List<ApiExtraction> apis = plan.extraction().apis().stream()
				.map(api -> api.name().equals("orders")
						? new ApiExtraction(api.name(), api.path(), api.joinOn(),
								pagination, api.fields(), api.collapse())
						: api)
				.toList();
		Extraction extraction = new Extraction(plan.extraction().baseUrl(),
				plan.extraction().merge(), apis);
		return new ExecutionPlan(plan.slug(), plan.engine(), plan.executionModel(),
				plan.schedule(), extraction, plan.transforms(), plan.files(),
				plan.staging(), plan.delivery());
	}

	private static Map<String, Object> read(String json) {
		return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
		});
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> section(Map<String, Object> parent, String key) {
		return (Map<String, Object>) parent.get(key);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> components(Map<String, Object> contents,
			String key) {
		return (List<Map<String, Object>>) contents.get(key);
	}

	/** The compiler's own serialization discipline, applied to the subgraph. */
	private static String prettyPrinted(Map<String, Object> subgraph) {
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter(
				Separators.createDefaultInstance()
						.withObjectNameValueSpacing(Separators.Spacing.AFTER)
						.withObjectEmptySeparator("")
						.withArrayEmptySeparator(""))
				.withObjectIndenter(new DefaultIndenter("  ", "\n"))
				.withArrayIndenter(new DefaultIndenter("  ", "\n"));
		return MAPPER.writer().with(printer).writeValueAsString(subgraph) + "\n";
	}

	private String envValue(String key) throws Exception {
		return Files.readAllLines(Path.of("../infra/.env")).stream()
				.filter(line -> line.startsWith(key + "="))
				.map(line -> line.substring(key.length() + 1)).findFirst().orElseThrow();
	}
}
