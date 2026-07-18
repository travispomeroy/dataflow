package dev.pomeroy.dataflow.controlplane.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.pomeroy.dataflow.controlplane.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The Catalog module's public Java API — the seam the compiler (M1.3) consumes. This is
 * where physical definitions are deliberately exposed: per-API pagination so engine
 * compilers can generate page loops, and the merge definition behind the Positions
 * projection. Expected values come from the fixtures contract
 * (infra/fixtures/README.md), not from the seeds themselves.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogModuleApiTests {

	@Autowired
	Catalog catalog;

	Source positions() {
		return catalog.sources().stream()
				.filter(source -> source.id().equals("positions"))
				.findFirst().orElseThrow();
	}

	@Test
	void positionsSourceCarriesPaginationConfigForAllThreeUpstreamApis() {
		assertThat(positions().physical().apis())
				.extracting(UpstreamApi::name, UpstreamApi::path,
						api -> api.pagination().style(), api -> api.pagination().pageSize(),
						api -> api.pagination().termination())
				.containsExactly(
						tuple("positions", "/positions", PaginationStyle.PAGE_NUMBER, 50,
								PaginationTermination.TOTAL_PAGES),
						tuple("investors", "/investors", PaginationStyle.PAGE_NUMBER, 4,
								PaginationTermination.TOTAL_PAGES),
						tuple("orders", "/orders", PaginationStyle.PAGE_NUMBER, 75,
								PaginationTermination.TOTAL_PAGES));
	}

	@Test
	void positionsMergeIsOnTheInternalIdWithTheOrderOverlapPair() {
		SourcePhysical physical = positions().physical();

		assertThat(physical.baseUrl()).isEqualTo("http://wiremock:8080");
		assertThat(physical.merge().key()).isEqualTo("investorId");
		assertThat(physical.apis())
				.extracting(UpstreamApi::name, UpstreamApi::joinOn)
				.containsExactly(
						tuple("positions", List.<String>of()),
						tuple("investors", List.of("investorId")),
						tuple("orders", List.of("investorId", "symbol")));
	}

	@Test
	void pomeroyProviderPhysicalDefinitionNamesAKestraSecretInsteadOfCarryingOne() {
		Destination pomeroy = catalog.destinations().stream()
				.filter(destination -> destination.id().equals("pomeroy-provider"))
				.findFirst().orElseThrow();
		DestinationPhysical physical = pomeroy.physical();

		assertThat(physical.host()).isEqualTo("sftp");
		assertThat(physical.port()).isEqualTo(22);
		assertThat(physical.username()).isEqualTo("pomeroy");
		assertThat(physical.basePath()).isEqualTo("upload/pomeroy");
		assertThat(physical.credentialsRef()).isEqualTo("SFTP_POMEROY");
	}

	@Test
	void positionsProjectionRenamesUpstreamInvestorVocabularyToClient() {
		UpstreamApi investors = positions().physical().apis().stream()
				.filter(api -> api.name().equals("investors"))
				.findFirst().orElseThrow();

		assertThat(investors.fields())
				.containsEntry("investorId", "clientId")
				.containsEntry("name", "clientName")
				.containsEntry("advisorGroup", "advisorGroup");
	}
}
