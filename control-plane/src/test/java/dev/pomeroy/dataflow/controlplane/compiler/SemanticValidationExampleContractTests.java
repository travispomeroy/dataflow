package dev.pomeroy.dataflow.controlplane.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pomeroy.dataflow.controlplane.compiler.internal.CatalogResolvingCompiler;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfigModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared-example half of the semantic rule set's keep-honest contract (issue #30):
 * every annotated violation example in e2e/canonical/violations/ must produce exactly
 * the rule ids it declares — no more, no less, set semantics. The TS courtesy mirror
 * (ui/libs/dataflow-config, semantic-validation.spec.ts) asserts the same files, so an
 * example changed on one side breaks the other until both rule sets move together.
 * Surefire runs with the module base directory as working directory, so the repo root
 * is one level up.
 */
class SemanticValidationExampleContractTests {

	static final Path EXAMPLES_DIR = Path.of("../e2e/canonical/violations");

	static final Set<String> ALL_RULES = Set.of("delivery-count", "disconnected", "cycle",
			"non-linear", "headless-path", "dangling-path", "source-mid-path",
			"delivery-mid-path", "missing-operator-fields");

	static final ObjectMapper MAPPER =
			JsonMapper.builder().addModule(new DataflowConfigModule()).build();

	DataflowCompiler compiler = new CatalogResolvingCompiler(SeedCatalogs.fromCommittedSeeds());

	static Stream<Path> examples() throws IOException {
		try (Stream<Path> files = Files.list(EXAMPLES_DIR)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".violation.json"))
					.sorted()
					.toList()
					.stream();
		}
	}

	@ParameterizedTest
	@MethodSource("examples")
	void everyExampleYieldsExactlyItsDeclaredRuleIds(Path example) throws IOException {
		JsonNode document = MAPPER.readTree(Files.readString(example));
		List<String> declared = declaredRules(document);
		DataflowConfig config = MAPPER.treeToValue(document.get("config"), DataflowConfig.class);

		List<String> yielded = compiler.validate(config).stream()
				.map(SemanticViolation::rule)
				.distinct()
				.sorted()
				.toList();

		assertThat(yielded).as("declared rules are the sorted distinct ids the validator yields")
				.isEqualTo(declared);
	}

	@Test
	void theExamplesCoverEveryRuleIdAndProveAllRulesRun() throws IOException {
		List<List<String>> declaredSets = examples()
				.map(example -> {
					try {
						return declaredRules(MAPPER.readTree(Files.readString(example)));
					}
					catch (IOException e) {
						throw new RuntimeException(e);
					}
				})
				.toList();

		assertThat(declaredSets.stream().flatMap(List::stream))
				.as("every semantic rule id has at least one violation example")
				.containsAll(ALL_RULES);
		assertThat(declaredSets).as("multi-violation examples prove all rules run, not first-fail")
				.anyMatch(rules -> rules.size() >= 3);
	}

	private static List<String> declaredRules(JsonNode document) {
		return MAPPER.treeToValue(document.get("rules"), new TypeReference<List<String>>() {
		});
	}
}
