package dev.pomeroy.dataflow.controlplane.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.pomeroy.dataflow.controlplane.compiler.internal.CatalogResolvingCompiler;
import dev.pomeroy.dataflow.controlplane.dataflow.ClientFilterNode;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfigModule;
import dev.pomeroy.dataflow.controlplane.dataflow.DestinationNode;
import dev.pomeroy.dataflow.controlplane.dataflow.Edge;
import dev.pomeroy.dataflow.controlplane.dataflow.Node;
import dev.pomeroy.dataflow.controlplane.dataflow.SourceNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The deploy-time semantic rule set (issue #14, ADR-0005): the graph shapes Deploy
 * must reject, each with a distinct structured violation, and the canonical shape it
 * must accept. Structural validation (M1.2) already ran at save time — these rules
 * assume well-formed documents and judge the graph.
 */
class SemanticValidationTests {

	static final Path CANONICAL_EXAMPLE = Path.of("../e2e/canonical/positions-feed.config.json");

	ObjectMapper mapper = JsonMapper.builder().addModule(new DataflowConfigModule()).build();

	DataflowCompiler compiler = new CatalogResolvingCompiler(SeedCatalogs.fromCommittedSeeds());

	SourceNode source = new SourceNode("positions-source", "source", "positions");

	ClientFilterNode filter = new ClientFilterNode("client-filter", "transform", "clientFilter",
			List.of("INV-001"));

	DestinationNode destination = new DestinationNode("pomeroy-delivery", "destination",
			"pomeroy-provider");

	@Test
	void theCanonicalPositionsFeedHasNoViolations() throws Exception {
		DataflowConfig canonical = mapper.readValue(Files.readString(CANONICAL_EXAMPLE),
				DataflowConfig.class);

		assertThat(compiler.validate(canonical)).isEmpty();
	}

	@Test
	void aDisconnectedGraphIsRejected() {
		ClientFilterNode strayFilter = new ClientFilterNode("stray", "transform", "clientFilter",
				List.of());
		DataflowConfig config = deployable(
				List.of(source, filter, destination, strayFilter),
				List.of(new Edge("positions-source", "client-filter"),
						new Edge("client-filter", "pomeroy-delivery")));

		assertThat(rules(config)).contains("disconnected");
	}

	@Test
	void aCycleIsRejected() {
		ClientFilterNode secondFilter = new ClientFilterNode("second-filter", "transform",
				"clientFilter", List.of());
		DataflowConfig config = deployable(
				List.of(source, filter, secondFilter, destination),
				List.of(new Edge("positions-source", "client-filter"),
						new Edge("client-filter", "second-filter"),
						new Edge("second-filter", "client-filter"),
						new Edge("second-filter", "pomeroy-delivery")));

		assertThat(rules(config)).contains("cycle");
	}

	@Test
	void zeroDestinationsAreRejected() {
		DataflowConfig config = deployable(
				List.of(source, filter),
				List.of(new Edge("positions-source", "client-filter")));

		assertThat(rules(config)).contains("delivery-count");
	}

	@Test
	void twoDestinationsAreRejected() {
		DestinationNode second = new DestinationNode("second-delivery", "destination",
				"pomeroy-provider");
		DataflowConfig config = deployable(
				List.of(source, filter, destination, second),
				List.of(new Edge("positions-source", "client-filter"),
						new Edge("client-filter", "pomeroy-delivery"),
						new Edge("client-filter", "second-delivery")));

		assertThat(rules(config)).contains("delivery-count");
	}

	@Test
	void aDiamondIsRejectedAsNonLinearUntilM7() {
		ClientFilterNode left = new ClientFilterNode("left", "transform", "clientFilter", List.of());
		ClientFilterNode right = new ClientFilterNode("right", "transform", "clientFilter", List.of());
		DataflowConfig config = deployable(
				List.of(source, left, right, destination),
				List.of(new Edge("positions-source", "left"),
						new Edge("positions-source", "right"),
						new Edge("left", "pomeroy-delivery"),
						new Edge("right", "pomeroy-delivery")));

		assertThat(rules(config)).contains("non-linear")
				.doesNotContain("disconnected", "cycle", "delivery-count");
	}

	@Test
	void aPathEndingAnywhereButTheDeliveryIsRejected() {
		DataflowConfig config = deployable(
				List.of(source, filter, destination),
				List.of(new Edge("positions-source", "client-filter"),
						new Edge("pomeroy-delivery", "client-filter")));

		assertThat(rules(config)).contains("dangling-path", "delivery-mid-path");
	}

	@Test
	void aPathStartingAnywhereButASourceIsRejected() {
		ClientFilterNode headless = new ClientFilterNode("headless", "transform", "clientFilter",
				List.of());
		DataflowConfig config = deployable(
				List.of(source, filter, headless, destination),
				List.of(new Edge("positions-source", "client-filter"),
						new Edge("client-filter", "pomeroy-delivery"),
						new Edge("headless", "client-filter")));

		assertThat(rules(config)).contains("headless-path", "non-linear");
	}

	@Test
	void aSourceInTheMiddleOfAPathIsRejected() {
		SourceNode midSource = new SourceNode("mid-source", "source", "positions");
		DataflowConfig config = deployable(
				List.of(source, midSource, destination),
				List.of(new Edge("positions-source", "mid-source"),
						new Edge("mid-source", "pomeroy-delivery")));

		assertThat(rules(config)).contains("source-mid-path");
	}

	@Test
	void unsetOperatorFieldsAreRejectedAsNotCompilable() {
		DataflowConfig config = new DataflowConfig(
				List.of(source, filter, destination),
				List.of(new Edge("positions-source", "client-filter"),
						new Edge("client-filter", "pomeroy-delivery")),
				null, null, null);

		assertThat(rules(config)).containsExactly("missing-operator-fields");
	}

	@Test
	void anInvalidGraphReportsEveryApplicableViolationAtOnce() {
		ClientFilterNode stray = new ClientFilterNode("stray", "transform", "clientFilter", List.of());
		DataflowConfig config = deployable(
				List.of(source, filter, stray),
				List.of(new Edge("positions-source", "client-filter")));

		assertThat(rules(config)).contains("delivery-count", "disconnected", "dangling-path");
	}

	@Test
	void compileRefusesAConfigWithViolations() {
		DataflowConfig config = deployable(List.of(source, filter),
				List.of(new Edge("positions-source", "client-filter")));

		assertThatIllegalArgumentException()
				.isThrownBy(() -> compiler.compile("positions-feed", config))
				.withMessageContaining("delivery-count");
	}

	private List<String> rules(DataflowConfig config) {
		return compiler.validate(config).stream().map(SemanticViolation::rule).toList();
	}

	/** A config with operator fields set, so only the graph under test can violate. */
	private DataflowConfig deployable(List<Node> nodes, List<Edge> edges) {
		return new DataflowConfig(nodes, edges, null,
				dev.pomeroy.dataflow.controlplane.dataflow.Engine.HOP,
				dev.pomeroy.dataflow.controlplane.dataflow.ExecutionModel.BATCH);
	}
}
