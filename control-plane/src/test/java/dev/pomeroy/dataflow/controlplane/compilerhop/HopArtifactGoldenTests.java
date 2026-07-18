package dev.pomeroy.dataflow.controlplane.compilerhop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pomeroy.dataflow.controlplane.compiler.ApiExtraction;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlanJson;
import dev.pomeroy.dataflow.controlplane.compiler.Extraction;
import dev.pomeroy.dataflow.controlplane.compiler.Pagination;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationStyle;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationTermination;
import dev.pomeroy.dataflow.controlplane.compilerhop.internal.DeterministicHopXmlCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The Hop compiler's golden seam (issue #23, M1 golden-test prior art): the canonical
 * Positions Feed Execution Plan — read from its own committed golden, the module's
 * only input — compiles to a pipeline and workflow that byte-match the committed
 * goldens. Regenerate with {@code ./mvnw test -Dtest=HopArtifactGoldenTests
 * -Dregenerate.goldens} after a reviewed artifact-shape change — regeneration is
 * deterministic, so a rerun must produce byte-identical files. Surefire runs with the
 * module base directory as working directory, so the repo root is one level up.
 */
class HopArtifactGoldenTests {

	static final Path GOLDEN_PLAN = Path.of("../e2e/golden/positions-feed.plan.json");

	static final Path GOLDEN_PIPELINE = Path.of("../e2e/golden/positions-feed.hpl");

	static final Path GOLDEN_WORKFLOW = Path.of("../e2e/golden/positions-feed.hwf");

	HopPipelineCompiler compiler = new DeterministicHopXmlCompiler();

	@Test
	void theCanonicalPlanCompilesToTheCommittedGoldensByteForByte() throws Exception {
		HopArtifact artifact = compiler.compile(canonicalPlan());

		if (System.getProperty("regenerate.goldens") != null) {
			Files.createDirectories(GOLDEN_PIPELINE.getParent());
			Files.writeString(GOLDEN_PIPELINE, artifact.pipelineXml());
			Files.writeString(GOLDEN_WORKFLOW, artifact.workflowXml());
		}

		assertThat(artifact.pipelineFileName()).isEqualTo("positions-feed.hpl");
		assertThat(artifact.workflowFileName()).isEqualTo("positions-feed.hwf");
		assertThat(artifact.pipelineXml()).isEqualTo(Files.readString(GOLDEN_PIPELINE));
		assertThat(artifact.workflowXml()).isEqualTo(Files.readString(GOLDEN_WORKFLOW));
	}

	@Test
	void compilingTwiceProducesByteIdenticalOutput() throws Exception {
		HopArtifact first = compiler.compile(canonicalPlan());
		HopArtifact second = compiler.compile(canonicalPlan());

		assertThat(second).isEqualTo(first);
	}

	@Test
	void bothArtifactFilesCarryTheRunParameterContract() throws Exception {
		HopArtifact artifact = compiler.compile(canonicalPlan());

		for (String xml : List.of(artifact.pipelineXml(), artifact.workflowXml())) {
			assertThat(xml).contains("<name>RUN_ID</name>")
					.contains("<name>BUSINESS_DATE</name>").contains("<name>OUT_DIR</name>");
		}
	}

	/**
	 * The spec's extraction contract: every API gets an explicit generated page loop
	 * — a page-1 probe with the plan's page size, and URL-from-field fetches for the
	 * loop (2.18.1 has no native REST pagination; single-page is never special-cased).
	 */
	@Test
	void everyApiGetsItsOwnPageLoopWithThePlansPageSize() throws Exception {
		String pipeline = compiler.compile(canonicalPlan()).pipelineXml();

		assertThat(pipeline)
				.contains("<url>http://wiremock:8080/positions?page=1&amp;pageSize=50</url>")
				.contains("<url>http://wiremock:8080/investors?page=1&amp;pageSize=4</url>")
				.contains("<url>http://wiremock:8080/orders?page=1&amp;pageSize=75</url>");
		for (String api : List.of("positions", "investors", "orders")) {
			assertThat(pipeline).contains("<name>%s: read totalPages</name>".formatted(api))
					.contains("<name>%s: fetch every page</name>".formatted(api));
		}
	}

	/** ADR-0006: the orders merge is a latest-per-key reduction, before the merge. */
	@Test
	void theCollapsingApiReducesToTheLatestRecordBeforeItsMerge() throws Exception {
		String pipeline = compiler.compile(canonicalPlan()).pipelineXml();

		assertThat(pipeline).contains("<name>orders: keep the latest per key</name>");
		assertThat(pipeline.indexOf("orders: keep the latest per key"))
				.isLessThan(pipeline.indexOf("<name>merge orders</name>"));
	}

	/**
	 * Verbatim numerics (spike #22): pass-through fields ride as String end-to-end —
	 * no field the upstream APIs feed is ever typed numerically in the pipeline.
	 */
	@Test
	void everyExtractedDataFieldIsTypedString() throws Exception {
		String pipeline = compiler.compile(canonicalPlan()).pipelineXml();

		List<String> lines = pipeline.lines().toList();
		List<Integer> dataPathLines = IntStream.range(0, lines.size())
				.filter(i -> lines.get(i).contains("<path>$.data[*]."))
				.boxed().toList();
		assertThat(dataPathLines).isNotEmpty();
		for (int i : dataPathLines) {
			assertThat(lines.get(i + 1).trim()).isEqualTo("<type>String</type>");
		}
	}

	/**
	 * The workflow stages to the platform Staging convention with the Run late-bound,
	 * and the artifact never carries a credential — the mock world's MinIO root
	 * credentials are committed in infra/.env, so the test can grep for them
	 * literally (ADR-0002 as amended).
	 */
	@Test
	void theWorkflowStagesToMinioAndNeitherFileCarriesACredential() throws Exception {
		String minioUser = envValue("MINIO_ROOT_USER");
		String minioPassword = envValue("MINIO_ROOT_PASSWORD");

		HopArtifact artifact = compiler.compile(canonicalPlan());

		assertThat(artifact.workflowXml())
				.contains("minio://staging/positions-feed/${RUN_ID}");
		for (String xml : List.of(artifact.pipelineXml(), artifact.workflowXml())) {
			assertThat(xml).doesNotContain(minioUser).doesNotContain(minioPassword);
		}
	}

	/** The five per-Asset-Class files resolve their names fully inside the pipeline. */
	@Test
	void fileNamesResolveInsideThePipelineFromPatternBusinessDateAndSplitField()
			throws Exception {
		String pipeline = compiler.compile(canonicalPlan()).pipelineXml();

		assertThat(pipeline).contains("<nullif>/positions_</nullif>")
				.contains("<nullif>.csv</nullif>")
				.contains("<variable>${BUSINESS_DATE}</variable>")
				.contains("<fileNameField>targetFileName</fileNameField>")
				.contains("<name>write positions-by-asset-class</name>");
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
		return ExecutionPlanJson.read(Files.readString(GOLDEN_PLAN));
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

	private String envValue(String key) throws Exception {
		return Files.readAllLines(Path.of("../infra/.env")).stream()
				.filter(line -> line.startsWith(key + "="))
				.map(line -> line.substring(key.length() + 1)).findFirst().orElseThrow();
	}
}
