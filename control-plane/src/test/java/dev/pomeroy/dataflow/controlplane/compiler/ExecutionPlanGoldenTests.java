package dev.pomeroy.dataflow.controlplane.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.pomeroy.dataflow.controlplane.compiler.internal.CatalogResolvingCompiler;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfigModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The compiler's golden seam (issue #14): the canonical Positions Feed config
 * compiles to an Execution Plan that byte-matches the committed golden. Regenerate
 * with {@code ./mvnw test -Dtest=ExecutionPlanGoldenTests -Dregenerate.goldens} after
 * a reviewed plan-shape change — regeneration is deterministic, so a rerun must
 * produce a byte-identical file. Surefire runs with the module base directory as
 * working directory, so the repo root is one level up.
 */
class ExecutionPlanGoldenTests {

	static final Path CANONICAL_EXAMPLE = Path.of("../e2e/canonical/positions-feed.config.json");

	static final Path GOLDEN_PLAN = Path.of("../e2e/golden/positions-feed.plan.json");

	static final String SLUG = "positions-feed";

	DataflowCompiler compiler = new CatalogResolvingCompiler(SeedCatalogs.fromCommittedSeeds());

	@Test
	void theCanonicalPositionsFeedCompilesToTheCommittedGoldenByteForByte() throws Exception {
		String plan = ExecutionPlanJson.write(compiler.compile(SLUG, canonicalConfig()));

		if (System.getProperty("regenerate.goldens") != null) {
			Files.createDirectories(GOLDEN_PLAN.getParent());
			Files.writeString(GOLDEN_PLAN, plan);
		}

		assertThat(plan).isEqualTo(Files.readString(GOLDEN_PLAN));
	}

	@Test
	void compilingTwiceProducesByteIdenticalOutput() throws Exception {
		String first = ExecutionPlanJson.write(compiler.compile(SLUG, canonicalConfig()));
		String second = ExecutionPlanJson.write(compiler.compile(SLUG, canonicalConfig()));

		assertThat(second).isEqualTo(first);
	}

	@Test
	void thePlanRoundTripsThroughItsOwnDocumentForm() throws Exception {
		ExecutionPlan plan = compiler.compile(SLUG, canonicalConfig());

		assertThat(ExecutionPlanJson.read(ExecutionPlanJson.write(plan))).isEqualTo(plan);
	}

	/**
	 * ADR-0002 as amended: the plan carries the credential reference, never the value.
	 * The mock world's one secret is committed in infra/.env, so the test can grep for
	 * it literally.
	 */
	@Test
	void thePlanNamesTheCredentialButNeverItsValue() throws Exception {
		String sftpPassword = Files.readAllLines(Path.of("../infra/.env")).stream()
				.filter(line -> line.startsWith("SFTP_PASSWORD="))
				.map(line -> line.substring("SFTP_PASSWORD=".length()))
				.findFirst().orElseThrow();

		String plan = ExecutionPlanJson.write(compiler.compile(SLUG, canonicalConfig()));

		assertThat(plan).contains("\"credentialsRef\": \"SFTP_POMEROY\"")
				.doesNotContain(sftpPassword);
	}

	@Test
	void thePlanCarriesResolvedPaginationForAllThreeApisAndTheRunScopedConventions() throws Exception {
		ExecutionPlan plan = compiler.compile(SLUG, canonicalConfig());

		assertThat(plan.extraction().apis())
				.extracting(ApiExtraction::name)
				.containsExactly("positions", "investors", "orders");
		assertThat(plan.extraction().apis())
				.allSatisfy(api -> {
					assertThat(api.pagination().style()).isEqualTo(PaginationStyle.PAGE_NUMBER);
					assertThat(api.pagination().pageSize()).isPositive();
					assertThat(api.pagination().termination())
							.isEqualTo(PaginationTermination.TOTAL_PAGES);
				});
		assertThat(plan.transforms()).containsExactly(new ClientFilterStep("clientFilter",
				"clientId", List.of("INV-001", "INV-002", "INV-003")));
		assertThat(plan.files()).containsExactly(new OutputFile("positions-by-asset-class",
				"positions_{assetClass}_{businessDate}.csv", "assetClass",
				List.of("clientId", "clientName", "advisorGroup", "symbol", "assetClass",
						"quantity", "marketValue", "currency", "orderId", "orderSide",
						"orderQuantity", "orderStatus", "tradeDate")));
		assertThat(plan.staging()).isEqualTo(new Staging("positions-feed/{runId}/"));
	}

	/**
	 * ADR-0006: the orders extraction carries the latest-order collapse rule so an
	 * engine compiler can generate the whole position-grain artifact from the plan
	 * alone; the 1:1 APIs carry none.
	 */
	@Test
	void thePlanCarriesTheLatestOrderCollapseOnTheOrdersExtractionOnly() throws Exception {
		ExecutionPlan plan = compiler.compile(SLUG, canonicalConfig());

		assertThat(plan.extraction().apis())
				.extracting(ApiExtraction::name, ApiExtraction::collapse)
				.containsExactly(
						tuple("positions", null),
						tuple("investors", null),
						tuple("orders", new Collapse(List.of("tradeDate", "orderId"))));
	}

	private DataflowConfig canonicalConfig() throws Exception {
		return JsonMapper.builder().addModule(new DataflowConfigModule()).build()
				.readValue(Files.readString(CANONICAL_EXAMPLE), DataflowConfig.class);
	}
}
