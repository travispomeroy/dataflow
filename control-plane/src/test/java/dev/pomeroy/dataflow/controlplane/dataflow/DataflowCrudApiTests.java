package dev.pomeroy.dataflow.controlplane.dataflow;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pomeroy.dataflow.controlplane.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * The Dataflow draft CRUD seam (issue #12): a feed author can create a Dataflow, save
 * it in any half-built state, and read/update/delete it. Save-time validation is
 * structural only — semantic rules (connected, acyclic, linear) belong to Deploy
 * (M1.4/M1.6) and must NOT be enforced here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DataflowCrudApiTests {

	@Autowired
	MockMvc mvc;

	ObjectMapper mapper = new ObjectMapper();

	String draft(String name, String configJson) {
		return "{\"name\": " + mapper.writeValueAsString(name) + ", \"config\": " + configJson + "}";
	}

	String emptyDraft(String name) {
		return draft(name, "{\"nodes\": [], \"edges\": []}");
	}

	String create(String body) throws Exception {
		return mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	String idOf(String responseBody) {
		return mapper.readTree(responseBody).path("id").stringValue();
	}

	// --- create ---

	@Test
	void creatingADataflowMintsASlugFromTheInitialNameAndReturnsTheDraft() throws Exception {
		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(draft("The Positions Feed!", Files.readString(
						Path.of("../e2e/canonical/positions-feed.config.json")))))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", matchesPattern(".*/api/dataflows/[0-9a-f-]{36}")))
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.slug").value("the-positions-feed"))
				.andExpect(jsonPath("$.name").value("The Positions Feed!"))
				.andExpect(jsonPath("$.config.nodes", hasSize(3)))
				.andExpect(jsonPath("$.config.nodes[1].kind").value("clientFilter"))
				.andExpect(jsonPath("$.config.schedule.kind").value("daily"))
				.andExpect(jsonPath("$.config.engine").value("hop"));
	}

	@Test
	void aHalfBuiltGraphSavesSuccessfullyHoweverDisconnectedOrIncomplete() throws Exception {
		// A lone source, a disconnected client filter, no destination, no schedule,
		// no operator fields: structurally fine, semantically nowhere near deployable.
		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(draft("Half Built", """
						{
						  "nodes": [
						    { "id": "src", "type": "source", "sourceId": "positions" },
						    { "id": "flt", "type": "transform", "kind": "clientFilter",
						      "clientIds": ["INV-001"] }
						  ],
						  "edges": []
						}
						""")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.config.nodes", hasSize(2)))
				.andExpect(jsonPath("$.config.schedule").doesNotExist());
	}

	@Test
	void mintingTheSameSlugTwiceIsRejectedAsAConflictProblemDetail() throws Exception {
		create(emptyDraft("Duplicate Feed"));

		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(emptyDraft("Duplicate feed")))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("A Dataflow with slug 'duplicate-feed' already exists"));
	}

	@Test
	void aBlankNameCannotMintASlug() throws Exception {
		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(emptyDraft("  !?  ")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.violations[*].message",
						hasItem("name must contain at least one letter or digit")));
	}

	// --- structural validation ---

	@Test
	void aDanglingEdgeReferenceIsAStructuralViolation() throws Exception {
		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(draft("Dangling Edge", """
						{
						  "nodes": [{ "id": "src", "type": "source", "sourceId": "positions" }],
						  "edges": [{ "from": "src", "to": "ghost" }]
						}
						""")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(422))
				.andExpect(jsonPath("$.violations", hasSize(1)))
				.andExpect(jsonPath("$.violations[0].message")
						.value("edge src -> ghost references unknown node id 'ghost'"));
	}

	@Test
	void unresolvableCatalogRefsAreStructuralViolationsReportedTogether() throws Exception {
		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(draft("Unknown Refs", """
						{
						  "nodes": [
						    { "id": "src", "type": "source", "sourceId": "orders" },
						    { "id": "flt", "type": "transform", "kind": "clientFilter",
						      "clientIds": ["INV-001", "INV-999"] },
						    { "id": "dst", "type": "destination", "destinationId": "acme-provider" }
						  ],
						  "edges": [
						    { "from": "src", "to": "flt" },
						    { "from": "flt", "to": "dst" }
						  ]
						}
						""")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.violations", hasSize(3)))
				.andExpect(jsonPath("$.violations[*].message", hasItem(
						"node 'src' references unknown Source 'orders'")))
				.andExpect(jsonPath("$.violations[*].message", hasItem(
						"node 'flt' references unknown Client 'INV-999'")))
				.andExpect(jsonPath("$.violations[*].message", hasItem(
						"node 'dst' references unknown Destination 'acme-provider'")));
	}

	@Test
	void anUnknownNodeTypeIsAStructuralViolation() throws Exception {
		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(draft("Unknown Type", """
						{ "nodes": [{ "id": "n1", "type": "teleport" }], "edges": [] }
						""")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.violations[*].message", hasItem(
						"unknown node type 'teleport' (expected source, transform or destination)")));
	}

	@Test
	void anUnknownTransformKindIsAStructuralViolation() throws Exception {
		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(draft("Unknown Kind", """
						{ "nodes": [{ "id": "n1", "type": "transform", "kind": "quantum" }], "edges": [] }
						""")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.violations[*].message", hasItem(
						"unknown transform kind 'quantum' (expected clientFilter)")));
	}

	@Test
	void aDuplicateNodeIdIsAStructuralViolation() throws Exception {
		mvc.perform(post("/api/dataflows").contentType(MediaType.APPLICATION_JSON)
				.content(draft("Duplicate Node Id", """
						{
						  "nodes": [
						    { "id": "n1", "type": "source", "sourceId": "positions" },
						    { "id": "n1", "type": "destination", "destinationId": "pomeroy-provider" }
						  ],
						  "edges": []
						}
						""")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.violations[*].message", hasItem("duplicate node id 'n1'")));
	}

	// --- read / update / delete ---

	@Test
	void aSavedDraftReadsBackExactlyAndAppearsInTheList() throws Exception {
		String id = idOf(create(draft("Read Back", """
				{
				  "nodes": [{ "id": "src", "type": "source", "sourceId": "positions" }],
				  "edges": []
				}
				""")));

		mvc.perform(get("/api/dataflows/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.slug").value("read-back"))
				.andExpect(jsonPath("$.config.nodes[0].sourceId").value("positions"));

		mvc.perform(get("/api/dataflows"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].slug", hasItem("read-back")));
	}

	@Test
	void renamingChangesTheDisplayNameOnlyTheSlugIsImmutable() throws Exception {
		String id = idOf(create(emptyDraft("Original Name")));

		mvc.perform(put("/api/dataflows/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(emptyDraft("A Totally New Name")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("A Totally New Name"))
				.andExpect(jsonPath("$.slug").value("original-name"));

		mvc.perform(get("/api/dataflows/" + id))
				.andExpect(jsonPath("$.slug").value("original-name"));
	}

	@Test
	void renamingObeysTheSameNameRuleAsCreation() throws Exception {
		String id = idOf(create(emptyDraft("Rename Me")));

		mvc.perform(put("/api/dataflows/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(emptyDraft("$$$")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.violations[*].message",
						hasItem("name must contain at least one letter or digit")));
	}

	@Test
	void updatingReplacesTheDraftConfigAndStillValidatesStructure() throws Exception {
		String id = idOf(create(emptyDraft("Growing Draft")));

		mvc.perform(put("/api/dataflows/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(draft("Growing Draft", """
						{
						  "nodes": [{ "id": "src", "type": "source", "sourceId": "positions" }],
						  "edges": []
						}
						""")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.config.nodes", hasSize(1)));

		mvc.perform(put("/api/dataflows/" + id).contentType(MediaType.APPLICATION_JSON)
				.content(draft("Growing Draft", """
						{ "nodes": [], "edges": [{ "from": "a", "to": "b" }] }
						""")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.violations", hasSize(2)));
	}

	@Test
	void deletingANeverDeployedDraftRemovesIt() throws Exception {
		String id = idOf(create(emptyDraft("Short Lived")));

		mvc.perform(delete("/api/dataflows/" + id)).andExpect(status().isNoContent());
		mvc.perform(get("/api/dataflows/" + id))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void unknownDataflowIdsAreNotFoundProblemDetails() throws Exception {
		String ghost = "00000000-0000-0000-0000-000000000000";
		mvc.perform(get("/api/dataflows/" + ghost)).andExpect(status().isNotFound());
		mvc.perform(put("/api/dataflows/" + ghost).contentType(MediaType.APPLICATION_JSON)
				.content(emptyDraft("Ghost"))).andExpect(status().isNotFound());
		mvc.perform(delete("/api/dataflows/" + ghost)).andExpect(status().isNotFound());
	}
}
