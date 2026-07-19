package dev.pomeroy.dataflow.controlplane.compiler.internal;

import dev.pomeroy.dataflow.controlplane.catalog.Catalog;
import dev.pomeroy.dataflow.controlplane.catalog.Destination;
import dev.pomeroy.dataflow.controlplane.catalog.DestinationPhysical;
import dev.pomeroy.dataflow.controlplane.catalog.Source;
import dev.pomeroy.dataflow.controlplane.catalog.SourcePhysical;
import dev.pomeroy.dataflow.controlplane.compiler.ApiExtraction;
import dev.pomeroy.dataflow.controlplane.compiler.ClientFilterStep;
import dev.pomeroy.dataflow.controlplane.compiler.Collapse;
import dev.pomeroy.dataflow.controlplane.compiler.DataflowCompiler;
import dev.pomeroy.dataflow.controlplane.compiler.Delivery;
import dev.pomeroy.dataflow.controlplane.compiler.Engine;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionModel;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.Extraction;
import dev.pomeroy.dataflow.controlplane.compiler.Merge;
import dev.pomeroy.dataflow.controlplane.compiler.OutputFile;
import dev.pomeroy.dataflow.controlplane.compiler.Pagination;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationStyle;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationTermination;
import dev.pomeroy.dataflow.controlplane.compiler.Schedule;
import dev.pomeroy.dataflow.controlplane.compiler.ScheduleKind;
import dev.pomeroy.dataflow.controlplane.compiler.SemanticViolation;
import dev.pomeroy.dataflow.controlplane.compiler.Staging;
import dev.pomeroy.dataflow.controlplane.compiler.TransformStep;
import dev.pomeroy.dataflow.controlplane.dataflow.ClientFilterNode;
import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import dev.pomeroy.dataflow.controlplane.dataflow.DestinationNode;
import dev.pomeroy.dataflow.controlplane.dataflow.Edge;
import dev.pomeroy.dataflow.controlplane.dataflow.Node;
import dev.pomeroy.dataflow.controlplane.dataflow.SourceNode;
import dev.pomeroy.dataflow.controlplane.dataflow.TransformNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The compiler: semantic validation over the config graph, then Catalog references
 * resolved into the Execution Plan. Reads only the config and catalog module APIs —
 * engine compilers read the plans this produces, never the Catalog (ADR-0002).
 */
@Component
public class CatalogResolvingCompiler implements DataflowCompiler {

	private final Catalog catalog;

	private final SemanticValidator validator = new SemanticValidator();

	public CatalogResolvingCompiler(Catalog catalog) {
		this.catalog = catalog;
	}

	@Override
	public List<SemanticViolation> validate(DataflowConfig config) {
		return validator.validate(config);
	}

	@Override
	public ExecutionPlan compile(String slug, DataflowConfig config) {
		List<SemanticViolation> violations = validate(config);
		if (!violations.isEmpty()) {
			throw new IllegalArgumentException("config has semantic violations: " + violations
					.stream().map(SemanticViolation::rule).collect(Collectors.joining(", ")));
		}

		List<Node> chain = linearChain(config);
		Source source = source(((SourceNode) chain.getFirst()).sourceId());
		Destination destination = destination(
				((DestinationNode) chain.getLast()).destinationId());

		return new ExecutionPlan(slug,
				switch (config.engine()) {
					case HOP -> Engine.HOP;
					case NIFI -> Engine.NIFI;
				},
				switch (config.executionModel()) {
					case SERVER -> ExecutionModel.SERVER;
					case BATCH -> ExecutionModel.BATCH;
				},
				config.schedule() == null ? null
						: new Schedule(switch (config.schedule().kind()) {
							case DAILY -> ScheduleKind.DAILY;
						}, config.schedule().time(), config.schedule().timezone()),
				extraction(source.physical()),
				chain.subList(1, chain.size() - 1).stream()
						.map(node -> transformStep((TransformNode) node, source.physical()))
						.toList(),
				catalog.fileDefinitions().stream()
						.filter(definition -> definition.sourceId().equals(source.id()))
						.map(definition -> new OutputFile(definition.id(),
								definition.namePattern(), definition.splitBy(),
								definition.splitValues(), definition.columns()))
						.toList(),
				new Staging(slug + "/{runId}/"),
				delivery(destination));
	}

	/**
	 * The validated graph is a single Source → … → Delivery chain (linear until M7);
	 * walking edges from the Source recovers execution order.
	 */
	private List<Node> linearChain(DataflowConfig config) {
		Map<String, Node> byId = config.nodes().stream()
				.collect(Collectors.toMap(Node::id, Function.identity()));
		Map<String, String> next = config.edges().stream()
				.collect(Collectors.toMap(Edge::from, Edge::to));

		List<Node> chain = new ArrayList<>();
		Node node = config.nodes().stream()
				.filter(SourceNode.class::isInstance)
				.findFirst().orElseThrow();
		while (node != null) {
			chain.add(node);
			node = byId.get(next.get(node.id()));
		}
		return chain;
	}

	private Extraction extraction(SourcePhysical physical) {
		return new Extraction(physical.baseUrl(), new Merge(physical.merge().key()),
				physical.apis().stream()
						.map(api -> new ApiExtraction(api.name(), api.path(), api.joinOn(),
								new Pagination(switch (api.pagination().style()) {
									case PAGE_NUMBER -> PaginationStyle.PAGE_NUMBER;
								}, api.pagination().pageSize(),
										switch (api.pagination().termination()) {
											case TOTAL_PAGES -> PaginationTermination.TOTAL_PAGES;
										}),
								api.fields(),
								api.collapse() == null ? null
										: new Collapse(api.collapse().latestBy())))
						.toList());
	}

	private TransformStep transformStep(TransformNode node, SourcePhysical physical) {
		return switch (node) {
			case ClientFilterNode filter -> new ClientFilterStep("clientFilter",
					clientField(physical), filter.clientIds());
		};
	}

	/**
	 * The logical column a client filter applies to: the merge key as projected by
	 * whichever API renames it (upstream "investorId" becomes "clientId" at the Source
	 * boundary). Falls back to the raw key if no API projects it.
	 */
	private String clientField(SourcePhysical physical) {
		return physical.apis().stream()
				.map(api -> api.fields().get(physical.merge().key()))
				.filter(field -> field != null)
				.findFirst()
				.orElse(physical.merge().key());
	}

	private Delivery delivery(Destination destination) {
		DestinationPhysical physical = destination.physical();
		return new Delivery(physical.host(), physical.port(), physical.username(),
				physical.basePath(), physical.credentialsRef());
	}

	private Source source(String id) {
		return catalog.sources().stream().filter(source -> source.id().equals(id))
				.findFirst().orElseThrow(() -> new IllegalArgumentException(
						"config references unknown Source '%s'".formatted(id)));
	}

	private Destination destination(String id) {
		return catalog.destinations().stream()
				.filter(destination -> destination.id().equals(id))
				.findFirst().orElseThrow(() -> new IllegalArgumentException(
						"config references unknown Destination '%s'".formatted(id)));
	}
}
