package dev.pomeroy.dataflow.controlplane.catalog;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pomeroy.dataflow.controlplane.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Catalog read API — the seam feed authors (and the M3 canvas palette) consume.
 * Users see logical names only; endpoints, credentials and merge mechanics never
 * appear in a response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogReadApiTests {

	@Autowired
	MockMvc mvc;

	@Test
	void sourcesListsThePositionsSourceByLogicalNameOnly() throws Exception {
		mvc.perform(get("/api/catalog/sources"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value("positions"))
				.andExpect(jsonPath("$[0].name").value("Positions"))
				.andExpect(jsonPath("$[0].physical").doesNotExist())
				.andExpect(content().string(not(containsString("wiremock"))))
				.andExpect(content().string(not(containsString("investorId"))));
	}

	@Test
	void destinationsListsThePomeroyProviderByLogicalNameOnly() throws Exception {
		mvc.perform(get("/api/catalog/destinations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value("pomeroy-provider"))
				.andExpect(jsonPath("$[0].name").value("Pomeroy Provider"))
				.andExpect(jsonPath("$[0].physical").doesNotExist())
				.andExpect(content().string(not(containsString("sftp"))))
				.andExpect(content().string(not(containsString("credentialsRef"))));
	}

	@Test
	void fileDefinitionsCarryTheTokenNamePatternSplitByAssetClass() throws Exception {
		mvc.perform(get("/api/catalog/file-definitions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value("positions-by-asset-class"))
				.andExpect(jsonPath("$[0].sourceId").value("positions"))
				.andExpect(jsonPath("$[0].namePattern").value("positions_{assetClass}_{businessDate}.csv"))
				.andExpect(jsonPath("$[0].splitBy").value("assetClass"));
	}

	@Test
	void clientsServesReferenceDataInClientVocabulary() throws Exception {
		mvc.perform(get("/api/clients"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(10)))
				.andExpect(jsonPath("$[0].id").value("INV-001"))
				.andExpect(jsonPath("$[0].name").value("Alice Thornton"))
				.andExpect(jsonPath("$[0].advisorGroup").value("Meridian Wealth"))
				.andExpect(content().string(not(containsString("investorId"))));
	}

	@Test
	void theCatalogIsReadOnlySoWritesAreRejectedAsProblemDetails() throws Exception {
		mvc.perform(post("/api/catalog/sources").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(405));
	}

	@Test
	void unknownPathsAreProblemDetails() throws Exception {
		mvc.perform(get("/api/catalog/business-rules"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404));
	}
}
