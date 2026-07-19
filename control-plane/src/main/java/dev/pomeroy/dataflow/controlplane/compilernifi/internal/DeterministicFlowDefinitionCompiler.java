package dev.pomeroy.dataflow.controlplane.compilernifi.internal;

import dev.pomeroy.dataflow.controlplane.compiler.ApiExtraction;
import dev.pomeroy.dataflow.controlplane.compiler.ClientFilterStep;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.OutputFile;
import dev.pomeroy.dataflow.controlplane.compiler.Pagination;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationStyle;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationTermination;
import dev.pomeroy.dataflow.controlplane.compiler.TransformStep;
import dev.pomeroy.dataflow.controlplane.compilernifi.NiFiArtifact;
import dev.pomeroy.dataflow.controlplane.compilernifi.NiFiFlowCompiler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the flow-definition snapshot as plain ordered maps — no NiFi library, so
 * every byte is owned here and the committed goldens stay a valid comparison (the
 * Hop and Kestra compilers' discipline). Component shapes are copied from what NiFi
 * 2.10.0 itself serializes (the M4.2 spike's live export, kept as the committed
 * {@code templates/} resources), never invented.
 *
 * <p>The generated page loop is a genuinely sequential self-loop — the graph is
 * deliberately cyclic: {@code InvokeHTTP} reads {@code ${page}} from an attribute,
 * the loop branch reads {@code totalPages} from the envelope and routes back while
 * pages remain, and every page carries fragment attributes so a record-aware
 * defragment ({@code MergeRecord}) reassembles the dataset in page order — a dropped
 * page cannot defragment, so a missing page is impossible by construction (spikes
 * #38/#39). Pass-through fields ride as String end-to-end: every reader and writer
 * carries an explicit all-string schema, no inference anywhere (the M2 verbatim-
 * numerics lesson).
 *
 * <p>Platform conventions live here, not in the plan: the Staging store (bucket
 * {@code staging} on the compose MinIO, path-style access), the parameter names the
 * artifact references (per-run {@code businessDate}/{@code runId}, sensitive
 * {@code minioAccessKey}/{@code minioSecretKey} — ADR-0002, names only), and the
 * single failure funnel every failure relationship routes to, whose connection ids
 * the run driver polls (spike #38).
 */
@Component
public class DeterministicFlowDefinitionCompiler implements NiFiFlowCompiler {

	private static final String STAGING_BUCKET = "staging";

	private static final String STAGING_ENDPOINT = "http://minio:9000";

	private static final String STAGING_REGION = "us-east-1";

	@Override
	public NiFiArtifact compile(ExecutionPlan plan) {
		plan.extraction().apis()
				.forEach(DeterministicFlowDefinitionCompiler::requireSupportedPagination);
		Build build = new Build(plan);
		return new NiFiArtifact(plan.slug() + ".flow-definition.json",
				FlowDefinitionJson.write(build.document()), build.seedIds,
				build.failureConnectionIds);
	}

	/**
	 * The generated loop's termination contract is the Catalog's
	 * {@code pageNumber}/{@code totalPages} scheme; any other pagination must refuse
	 * to compile rather than silently extract a single page (standing rule: never
	 * special-case single-page).
	 */
	private static void requireSupportedPagination(ApiExtraction api) {
		Pagination pagination = api.pagination();
		if (pagination == null || pagination.style() != PaginationStyle.PAGE_NUMBER
				|| pagination.termination() != PaginationTermination.TOTAL_PAGES) {
			throw new UnsupportedOperationException(
					("the nifi compiler generates page loops only for pageNumber/totalPages"
							+ " pagination; api '%s' declares %s").formatted(api.name(),
									pagination == null ? "none"
											: "%s/%s".formatted(pagination.style(),
													pagination.termination())));
		}
	}

	/** One compilation: accumulates components and wiring, then folds the envelope. */
	private static final class Build {

		private static final ObjectMapper MAPPER = JsonMapper.builder().build();

		private static final Pattern NAME_TOKEN = Pattern.compile("\\{([^}]+)}");

		/** A placed component, carried around for wiring: logical path, minted id. */
		private record Ref(String path, String id, String type, String name) {
		}

		/** A dataset hand-off: the component producing it and its out-relationship. */
		private record Out(Ref ref, String relationship) {
		}

		private final ExecutionPlan plan;

		private final String pgId;

		private final List<Map<String, Object>> processors = new ArrayList<>();

		private final List<Map<String, Object>> connections = new ArrayList<>();

		private final List<Map<String, Object>> controllerServices = new ArrayList<>();

		private final Ref funnel;

		final List<String> seedIds = new ArrayList<>();

		final List<String> failureConnectionIds = new ArrayList<>();

		Build(ExecutionPlan plan) {
			this.plan = plan;
			this.pgId = mint("process-group");
			this.funnel = new Ref("funnel/failures", mint("funnel/failures"), "FUNNEL",
					"Funnel");
		}

		private String mint(String path) {
			return Uuid5.mint(plan.slug() + "/" + path);
		}

		Map<String, Object> document() {
			List<ApiExtraction> apis = plan.extraction().apis();
			List<ApiExtraction> mergers = apis.subList(1, apis.size());

			// -- record services: per-API readers/writers, per-merge, per-file ----
			List<Ref> pageReaders = new ArrayList<>();
			List<Ref> flatReaders = new ArrayList<>();
			List<Ref> jsonWriters = new ArrayList<>();
			for (ApiExtraction api : apis) {
				String schema = apiSchema(api);
				pageReaders.add(jsonTreeReader("api/" + api.name() + "/page-reader",
						api.name() + "-page-reader", schema, true));
				flatReaders.add(jsonTreeReader("api/" + api.name() + "/flat-reader",
						api.name() + "-flat-reader", schema, false));
				jsonWriters.add(jsonWriter("api/" + api.name() + "/json-writer",
						api.name() + "-json-writer", schema));
			}

			// column bookkeeping across the merge chain: logical column order, which
			// side provides each, and nullability (enrichment-provided ⇒ nullable)
			List<String> accumulated = new ArrayList<>(logicalColumns(apis.getFirst()));
			LinkedHashSet<String> nullable = new LinkedHashSet<>();
			List<Ref> stageWriters = new ArrayList<>();
			List<Ref> stageFlatReaders = new ArrayList<>();
			List<String> stageSqls = new ArrayList<>();
			Map<String, String> atStage = new LinkedHashMap<>();
			List<String> baseUpstream = upstreamColumns(apis.getFirst());
			List<String> baseLogical = logicalColumns(apis.getFirst());
			for (int i = 0; i < baseLogical.size(); i++) {
				atStage.put(baseLogical.get(i), baseUpstream.get(i));
			}
			for (ApiExtraction api : mergers) {
				stageSqls.add(mergeSql(api, accumulated, atStage, nullable));
				String schema = recordSchema("merge_" + avroName(api.name()) + "_row",
						accumulated, nullable);
				stageWriters.add(jsonWriter("merge/" + api.name() + "/json-writer",
						"merge-" + api.name() + "-json-writer", schema));
				stageFlatReaders.add(jsonTreeReader("merge/" + api.name() + "/flat-reader",
						"merge-" + api.name() + "-flat-reader", schema, false));
			}
			Ref feedFlatReader = mergers.isEmpty() ? flatReaders.getFirst()
					: stageFlatReaders.getLast();
			Ref feedJsonWriter = mergers.isEmpty() ? jsonWriters.getFirst()
					: stageWriters.getLast();

			List<Ref> csvWriters = new ArrayList<>();
			for (OutputFile file : plan.files()) {
				csvWriters.add(csvWriter(file, recordSchema(
						avroName(file.fileDefinitionId()) + "_row", file.columns(), nullable)));
			}

			Ref credentials = service("minio-credentials", "minio-credentials",
					"AWSCredentialsProviderControllerService",
					orderedMap("Access Key ID", "#{minioAccessKey}",
							"Secret Access Key", "#{minioSecretKey}"));

			// -- the cyclic page loop per API, then its optional collapse ---------
			List<Out> datasets = new ArrayList<>();
			for (int i = 0; i < apis.size(); i++) {
				datasets.add(extractionLoop(apis.get(i), i, pageReaders.get(i),
						flatReaders.get(i), jsonWriters.get(i)));
			}

			// -- the merge chain: tag pairs feeding chained JoinEnrichments -------
			Out current = datasets.getFirst();
			for (int k = 1; k <= mergers.size(); k++) {
				ApiExtraction api = mergers.get(k - 1);
				String stage = "merge " + api.name();
				Ref originalReader = k == 1 ? flatReaders.getFirst()
						: stageFlatReaders.get(k - 2);
				Ref tagOriginal = tag("merge/" + api.name() + "/tag-original",
						stage + ": tag original", api.name(), "ORIGINAL",
						250 * (k - 1), 900 + 300 * (k - 1));
				Ref tagEnrichment = tag("merge/" + api.name() + "/tag-enrichment",
						stage + ": tag enrichment", api.name(), "ENRICHMENT",
						500 * k, 900 + 150 * (k - 1));
				Ref merge = processor("merge/" + api.name() + "/join", stage,
						"JoinEnrichment", 250 + 350 * (k - 1), 1050 + 300 * (k - 1),
						orderedMap("Original Record Reader", originalReader.id(),
								"Enrichment Record Reader", flatReaders.get(apis.indexOf(api)).id(),
								"Record Writer", stageWriters.get(k - 1).id(),
								"Join Strategy", "SQL",
								"SQL", stageSqls.get(k - 1)),
						orderedMap(), List.of("original"), false);
				connect(current.ref(), List.of(current.relationship()), tagOriginal);
				Out enrichment = datasets.get(apis.indexOf(api));
				connect(enrichment.ref(), List.of(enrichment.relationship()), tagEnrichment);
				connect(tagOriginal, List.of("success"), merge);
				connect(tagEnrichment, List.of("success"), merge);
				toFunnel(merge, List.of("failure", "timeout"));
				current = new Out(merge, "joined");
			}

			// -- transforms, the structural split, file naming, staging -----------
			int stages = mergers.size();
			int x = stages > 0 ? 250 + 350 * (stages - 1) : 0;
			int y = stages > 0 ? 1050 + 300 * (stages - 1) : 900;
			List<TransformStep> transforms = plan.transforms();
			for (int t = 0; t < transforms.size(); t++) {
				// exhaustive over the sealed TransformStep: a new kind refuses to
				// compile here instead of failing at runtime
				ClientFilterStep step = switch (transforms.get(t)) {
					case ClientFilterStep filter -> filter;
				};
				y += 150;
				Ref filter = processor("transform/" + t,
						t == 0 ? "client filter" : "client filter " + (t + 1),
						"QueryRecord", x, y,
						orderedMap("Record Reader", feedFlatReader.id(),
								"Record Writer", feedJsonWriter.id()),
						orderedMap("filtered", filterSql(step)),
						List.of("original"), false);
				connect(current.ref(), List.of(current.relationship()), filter);
				toFunnel(filter, List.of("failure"));
				current = new Out(filter, "filtered");
			}

			List<String> sortKeys = sortKeys(apis);
			y += 150;
			int namerIndex = 0;
			Ref stage = processor("stage-to-minio", "stage to MinIO", "PutS3Object",
					x, y + 300,
					orderedMap("Bucket", STAGING_BUCKET,
							"Object Key", stagingPath() + "${filename}",
							"Region", STAGING_REGION,
							"AWS Credentials Provider Service", credentials.id(),
							"Endpoint Override URL", STAGING_ENDPOINT,
							"Use Path Style Access", "true"),
					orderedMap(), List.of("success"), false);
			for (int f = 0; f < plan.files().size(); f++) {
				OutputFile file = plan.files().get(f);
				Map<String, Object> splits = new LinkedHashMap<>();
				for (String value : file.splitValues()) {
					splits.put(value, splitSql(file, value, sortKeys));
				}
				Ref split = processor("file/" + file.fileDefinitionId() + "/split",
						"split " + file.fileDefinitionId(), "QueryRecord",
						x + 350 * f, y,
						orderedMap("Record Reader", feedFlatReader.id(),
								"Record Writer", csvWriters.get(f).id()),
						splits, List.of("original"), false);
				connect(current.ref(), List.of(current.relationship()), split);
				toFunnel(split, List.of("failure"));
				for (String value : file.splitValues()) {
					Ref namer = processor(
							"file/" + file.fileDefinitionId() + "/filename/" + value,
							plan.files().size() == 1 ? "filename: " + value
									: "filename: " + file.fileDefinitionId() + "/" + value,
							"UpdateAttribute", 100 + namerIndex * 320, y + 150,
							orderedMap(),
							orderedMap("filename", fileName(file, value)), List.of(),
							false);
					namerIndex++;
					connect(split, List.of(value), namer);
					connect(namer, List.of("success"), stage);
				}
			}
			toFunnel(stage, List.of("failure"));

			return envelope();
		}

		/**
		 * One API's sequential cursor loop: seed (DISABLED, RUN_ONCE at run time) →
		 * fetch → read totalPages → fragment attributes → route-back while pages
		 * remain, with the defragment reassembling the dataset in page order; then
		 * the ADR-0006 latest-record reduction when the plan declares one.
		 */
		private Out extractionLoop(ApiExtraction api, int index, Ref pageReader,
				Ref flatReader, Ref jsonWriter) {
			String name = api.name();
			int x = 500 * index;
			Ref seed = processor("api/" + name + "/seed", name + ": seed page 1",
					"GenerateFlowFile", x, 0,
					orderedMap("File Size", "0B", "Custom Text", "seed"),
					orderedMap("page", "1"), List.of(), true);
			seedIds.add(seed.id());
			Ref fetch = processor("api/" + name + "/fetch", name + ": fetch page",
					"InvokeHTTP", x, 150,
					orderedMap("HTTP Method", "GET",
							"HTTP URL", plan.extraction().baseUrl() + api.path()
									+ "?page=${page}&pageSize="
									+ api.pagination().pageSize()),
					orderedMap(), List.of("Original"), false);
			Ref totals = processor("api/" + name + "/total-pages",
					name + ": read totalPages", "EvaluateJsonPath", x, 300,
					orderedMap("Destination", "flowfile-attribute"),
					orderedMap("totalPages", "$.totalPages"), List.of(), false);
			Ref fragment = processor("api/" + name + "/fragment",
					name + ": fragment attributes", "UpdateAttribute", x, 450,
					orderedMap(),
					orderedMap("fragment.identifier", name,
							"fragment.index", "${page}",
							"fragment.count", "${totalPages}"),
					List.of(), false);
			Ref route = processor("api/" + name + "/route", name + ": more pages?",
					"RouteOnAttribute", x, 600, orderedMap(),
					orderedMap("next", "${page:lt(${totalPages})}"),
					List.of("unmatched"), false);
			Ref increment = processor("api/" + name + "/next-cursor",
					name + ": next cursor", "UpdateAttribute", x + 220, 150,
					orderedMap(), orderedMap("page", "${page:plus(1)}"), List.of(),
					false);
			Ref defragment = processor("api/" + name + "/defragment",
					name + ": defragment pages", "MergeRecord", x, 750,
					orderedMap("Record Reader", pageReader.id(),
							"Record Writer", jsonWriter.id(),
							"Merge Strategy", "Defragment",
							"Maximum Number of Records", "10000"),
					orderedMap(), List.of("original"), false);

			connect(seed, List.of("success"), fetch);
			connect(fetch, List.of("Response"), totals);
			connect(totals, List.of("matched"), fragment);
			connect(fragment, List.of("success"), defragment);
			connect(fragment, List.of("success"), route);
			connect(route, List.of("next"), increment);
			connect(increment, List.of("success"), fetch);
			toFunnel(fetch, List.of("Failure", "No Retry", "Retry"));
			toFunnel(totals, List.of("failure", "unmatched"));
			toFunnel(defragment, List.of("failure"));

			if (api.collapse() == null) {
				return new Out(defragment, "merged");
			}
			Ref collapse = processor("api/" + name + "/collapse",
					name + ": keep the latest per key", "QueryRecord", x, 900,
					orderedMap("Record Reader", flatReader.id(),
							"Record Writer", jsonWriter.id()),
					orderedMap("collapsed", collapseSql(api)), List.of("original"),
					false);
			connect(defragment, List.of("merged"), collapse);
			toFunnel(collapse, List.of("failure"));
			return new Out(collapse, "collapsed");
		}

		private Ref tag(String path, String name, String api, String role, int x, int y) {
			return processor(path, name, "UpdateAttribute", x, y, orderedMap(),
					orderedMap("enrichment.group.id", "merge-" + api,
							"enrichment.role", role),
					List.of(), false);
		}

		// -- SQL generation (shapes proven live in spike #39) --------------------

		/**
		 * One chained join stage: everything accumulated so far renamed to logical
		 * columns, the joining API's projections added (nullable — left join on the
		 * base grain), an explicit ORDER BY on the join-key columns so row order is
		 * never left to the SQL engine's discretion. Mutates the bookkeeping to the
		 * stage's output.
		 */
		private String mergeSql(ApiExtraction api, List<String> accumulated,
				Map<String, String> atStage, LinkedHashSet<String> nullable) {
			List<String> select = new ArrayList<>();
			for (String column : accumulated) {
				select.add("original.\"%s\" AS \"%s\"".formatted(atStage.get(column),
						column));
			}
			for (Map.Entry<String, String> field : api.fields().entrySet()) {
				if (!accumulated.contains(field.getValue())) {
					select.add("enrichment.\"%s\" AS \"%s\"".formatted(field.getKey(),
							field.getValue()));
				}
			}
			List<String> on = api.joinOn().stream()
					.map(key -> "original.\"%s\" = enrichment.\"%s\""
							.formatted(atStage.get(logicalName(key)), key))
					.toList();
			List<String> orderBy = sortKeys(plan.extraction().apis()).stream()
					.filter(accumulated::contains)
					.map(key -> "original.\"%s\"".formatted(atStage.get(key)))
					.toList();
			String sql = """
					SELECT
					  %s
					FROM original
					LEFT OUTER JOIN enrichment
					  ON %s
					ORDER BY %s""".formatted(String.join(",\n  ", select),
					String.join(" AND ", on), String.join(", ", orderBy));

			for (Map.Entry<String, String> field : api.fields().entrySet()) {
				if (!accumulated.contains(field.getValue())) {
					accumulated.add(field.getValue());
					nullable.add(field.getValue());
					atStage.put(field.getValue(), field.getValue());
				}
			}
			accumulated.forEach(column -> atStage.put(column, column));
			return sql;
		}

		/**
		 * The ADR-0006 reduction: latest record per join key — max of the first
		 * {@code latestBy} field, later ones breaking ties, all as lexicographic
		 * string comparisons. Every identifier quoted: unquoted ones get Calcite's
		 * case-folding and stop matching the record schema (spike #39).
		 */
		private String collapseSql(ApiExtraction api) {
			String columns = quoted(upstreamColumns(api));
			String partition = quoted(api.joinOn());
			String latest = commaJoined(api.collapse().latestBy().stream()
					.map(field -> "\"%s\" DESC".formatted(field)).toList());
			return """
					SELECT %s
					FROM (
					  SELECT *, ROW_NUMBER() OVER (PARTITION BY %s ORDER BY %s) AS "rn"
					  FROM FLOWFILE
					) AS "t"
					WHERE "rn" = 1""".formatted(columns, partition, latest);
		}

		private String filterSql(ClientFilterStep step) {
			String values = commaJoined(step.clientIds().stream()
					.map(id -> "'" + id.replace("'", "''") + "'").toList());
			return "SELECT * FROM FLOWFILE WHERE \"%s\" IN (%s)"
					.formatted(step.field(), values);
		}

		private String splitSql(OutputFile file, String value, List<String> sortKeys) {
			String orderBy = commaJoined(sortKeys.stream()
					.filter(file.columns()::contains).map("\"%s\""::formatted).toList());
			return "SELECT %s FROM FLOWFILE WHERE \"%s\" = '%s' ORDER BY %s".formatted(
					quoted(file.columns()), file.splitBy(),
					value.replace("'", "''"), orderBy);
		}

		private String quoted(List<String> identifiers) {
			return commaJoined(identifiers.stream().map("\"%s\""::formatted).toList());
		}

		/** An empty SQL fragment is never valid — refuse instead of serializing one. */
		private String commaJoined(List<String> parts) {
			if (parts.isEmpty()) {
				throw new IllegalArgumentException(
						"a SQL fragment of plan '%s' resolved to nothing"
								.formatted(plan.slug()));
			}
			return String.join(", ", parts);
		}

		// -- column vocabulary ---------------------------------------------------

		/** The upstream columns an API's page rows carry: its join keys, then fields. */
		private List<String> upstreamColumns(ApiExtraction api) {
			LinkedHashSet<String> columns = new LinkedHashSet<>();
			if (api.joinOn().isEmpty()) {
				columns.add(plan.extraction().merge().key());
			}
			columns.addAll(api.joinOn());
			columns.addAll(api.fields().keySet());
			return List.copyOf(columns);
		}

		private List<String> logicalColumns(ApiExtraction api) {
			return upstreamColumns(api).stream()
					.map(column -> api.fields().getOrDefault(column, logicalName(column)))
					.toList();
		}

		/**
		 * The logical name an internal column projects onto: whichever API renames it
		 * decides (upstream "investorId" becomes "clientId" at the Source boundary),
		 * falling back to the raw name — the resolving compiler's rule.
		 */
		private String logicalName(String internal) {
			return plan.extraction().apis().stream()
					.map(api -> api.fields().get(internal))
					.filter(logical -> logical != null)
					.findFirst().orElse(internal);
		}

		/**
		 * The deterministic row order every produced file shares: the union of all
		 * join keys in plan order, projected to logical names — unique on the
		 * delivered grain, so it fully determines row order (spike #39).
		 */
		private List<String> sortKeys(List<ApiExtraction> apis) {
			LinkedHashSet<String> keys = new LinkedHashSet<>();
			for (ApiExtraction api : apis) {
				api.joinOn().forEach(key -> keys.add(logicalName(key)));
			}
			if (keys.isEmpty()) {
				keys.add(logicalName(plan.extraction().merge().key()));
			}
			return List.copyOf(keys);
		}

		// -- schemas (all-string, explicit everywhere) ---------------------------

		private String apiSchema(ApiExtraction api) {
			return recordSchema(avroName(api.name()) + "_row", upstreamColumns(api),
					new LinkedHashSet<>());
		}

		private String recordSchema(String name, List<String> columns,
				LinkedHashSet<String> nullable) {
			List<Object> fields = new ArrayList<>();
			for (String column : columns) {
				Map<String, Object> field = new LinkedHashMap<>();
				field.put("name", column);
				if (nullable.contains(column)) {
					field.put("type", List.of("null", "string"));
					field.put("default", null);
				}
				else {
					field.put("type", "string");
				}
				fields.add(field);
			}
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("type", "record");
			record.put("name", name);
			record.put("fields", fields);
			return MAPPER.writeValueAsString(record);
		}

		private String avroName(String name) {
			String sanitized = name.replaceAll("[^A-Za-z0-9_]", "_");
			return sanitized.isEmpty() || Character.isDigit(sanitized.charAt(0))
					? "_" + sanitized : sanitized;
		}

		// -- file naming and staging ---------------------------------------------

		/**
		 * A File Definition token pattern resolved as far as compile time allows:
		 * the split field's token becomes the file's value, {@code {businessDate}}
		 * becomes its parameter reference; anything else refuses loudly.
		 */
		private String fileName(OutputFile file, String value) {
			Matcher matcher = NAME_TOKEN.matcher(file.namePattern());
			StringBuilder resolved = new StringBuilder();
			while (matcher.find()) {
				String token = matcher.group(1);
				if (token.equals(file.splitBy())) {
					matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
				}
				else if (token.equals("businessDate")) {
					matcher.appendReplacement(resolved, "#{businessDate}");
				}
				else {
					throw new IllegalArgumentException(
							"file definition '%s' carries token '{%s}' the nifi compiler cannot resolve"
									.formatted(file.fileDefinitionId(), token));
				}
			}
			matcher.appendTail(resolved);
			return resolved.toString();
		}

		private String stagingPath() {
			String path = plan.staging().pathConvention().replace("{runId}", "#{runId}");
			if (path.replace("#{runId}", "").contains("{")) {
				throw new IllegalArgumentException(
						"staging path convention '%s' carries a token the nifi compiler cannot resolve"
								.formatted(plan.staging().pathConvention()));
			}
			return path;
		}

		// -- component construction ----------------------------------------------

		private Ref processor(String path, String name, String typeSimpleName, int x,
				int y, Map<String, Object> properties, Map<String, Object> dynamics,
				List<String> autoTerminated, boolean seed) {
			Map<String, Object> template = ComponentTemplates.load(typeSimpleName);
			Map<String, Object> node = new LinkedHashMap<>();
			String id = mint("processor/" + path);
			node.put("identifier", id);
			node.put("name", name);
			node.put("comments", "");
			node.put("position", position(x, y));
			node.put("type", template.get("type"));
			node.put("bundle", template.get("bundle"));
			node.put("properties", overlay(template, properties, dynamics));
			node.put("propertyDescriptors", descriptors(template, dynamics));
			node.put("style", orderedMap());
			node.put("schedulingPeriod", seed ? "1 min" : "0 sec");
			node.put("schedulingStrategy", "TIMER_DRIVEN");
			node.put("executionNode", "ALL");
			node.put("penaltyDuration", "30 sec");
			node.put("yieldDuration", "1 sec");
			node.put("bulletinLevel", "WARN");
			node.put("runDurationMillis",
					typeSimpleName.equals("UpdateAttribute")
							|| typeSimpleName.equals("RouteOnAttribute") ? 25 : 0);
			node.put("concurrentlySchedulableTaskCount", 1);
			node.put("autoTerminatedRelationships", autoTerminated);
			// the seed arrives DISABLED so a process-group start skips it; the run
			// driver enables it and issues RUN_ONCE — no timer race (spike #38)
			node.put("scheduledState", seed ? "DISABLED" : "ENABLED");
			node.put("retryCount", 10);
			node.put("retriedRelationships", List.of());
			node.put("backoffMechanism", "PENALIZE_FLOWFILE");
			node.put("maxBackoffPeriod", "10 mins");
			node.put("componentType", "PROCESSOR");
			node.put("groupIdentifier", pgId);
			processors.add(node);
			return new Ref(path, id, "PROCESSOR", name);
		}

		private Ref jsonTreeReader(String path, String name, String schema,
				boolean pageEnvelope) {
			// NESTED_FIELD on the envelope's data field strips it with no extra
			// processor; unlisted envelope fields drop away by schema selection
			return service(path, name, "JsonTreeReader",
					orderedMap("Schema Access Strategy", "schema-text-property",
							"Schema Text", schema,
							"Starting Field Strategy",
							pageEnvelope ? "NESTED_FIELD" : "ROOT_NODE",
							"Starting Field Name", pageEnvelope ? "data" : null));
		}

		private Ref jsonWriter(String path, String name, String schema) {
			return service(path, name, "JsonRecordSetWriter",
					orderedMap("Schema Write Strategy", "no-schema",
							"Schema Access Strategy", "schema-text-property",
							"Schema Text", schema,
							"Pretty Print JSON", "false",
							"Output Grouping", "output-array"));
		}

		/** The golden byte contract: header, comma, LF, no enclosure (spike #39). */
		private Ref csvWriter(OutputFile file, String schema) {
			return service("file/" + file.fileDefinitionId() + "/csv-writer",
					file.fileDefinitionId() + "-csv-writer", "CSVRecordSetWriter",
					orderedMap("Schema Write Strategy", "no-schema",
							"Schema Access Strategy", "schema-text-property",
							"Schema Text", schema,
							"CSV Format", "custom",
							"CSV Writer", "commons-csv",
							"Value Separator", ",",
							"Record Separator", "\n",
							"Include Header Line", "true",
							"Quote Mode", "NONE",
							"Include Trailing Delimiter", "false",
							"Character Set", "UTF-8"));
		}

		private Ref service(String path, String name, String typeSimpleName,
				Map<String, Object> properties) {
			Map<String, Object> template = ComponentTemplates.load(typeSimpleName);
			Map<String, Object> node = new LinkedHashMap<>();
			String id = mint("service/" + path);
			node.put("identifier", id);
			node.put("name", name);
			node.put("comments", "");
			node.put("type", template.get("type"));
			node.put("bundle", template.get("bundle"));
			node.put("properties", overlay(template, properties, Map.of()));
			node.put("propertyDescriptors", descriptors(template, Map.of()));
			node.put("controllerServiceApis", template.get("controllerServiceApis"));
			// services always export DISABLED and arrive DISABLED; deploy enables
			// them explicitly (spike #38)
			node.put("scheduledState", "DISABLED");
			node.put("bulletinLevel", "WARN");
			node.put("componentType", "CONTROLLER_SERVICE");
			node.put("groupIdentifier", pgId);
			controllerServices.add(node);
			return new Ref(path, id, "CONTROLLER_SERVICE", name);
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> overlay(Map<String, Object> template,
				Map<String, Object> properties, Map<String, Object> dynamics) {
			Map<String, Object> merged = new LinkedHashMap<>(
					(Map<String, Object>) template.get("properties"));
			merged.putAll(properties);
			merged.putAll(dynamics);
			return merged;
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> descriptors(Map<String, Object> template,
				Map<String, Object> dynamics) {
			Map<String, Object> merged = new LinkedHashMap<>(
					(Map<String, Object>) template.get("propertyDescriptors"));
			for (String name : dynamics.keySet()) {
				Map<String, Object> descriptor = new LinkedHashMap<>();
				descriptor.put("name", name);
				descriptor.put("displayName", name);
				descriptor.put("identifiesControllerService", false);
				descriptor.put("sensitive", false);
				descriptor.put("dynamic", true);
				merged.put(name, descriptor);
			}
			return merged;
		}

		private void connect(Ref source, List<String> relationships, Ref destination) {
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("identifier",
					mint("connection/" + source.path() + "->" + destination.path()));
			node.put("name", source.name() + " -> " + destination.name());
			node.put("source", end(source));
			node.put("destination", end(destination));
			node.put("labelIndex", 1);
			node.put("zIndex", 0);
			node.put("selectedRelationships", relationships);
			node.put("backPressureObjectThreshold", 10000);
			node.put("backPressureDataSizeThreshold", "1 GB");
			node.put("flowFileExpiration", "0 sec");
			node.put("prioritizers", List.of());
			node.put("bends", List.of());
			node.put("loadBalanceStrategy", "DO_NOT_LOAD_BALANCE");
			node.put("loadBalanceCompression", "DO_NOT_COMPRESS");
			node.put("componentType", "CONNECTION");
			node.put("groupIdentifier", pgId);
			connections.add(node);
		}

		/** Failures stay visible in a queue, never auto-terminated (spec #37). */
		private void toFunnel(Ref source, List<String> relationships) {
			connect(source, relationships, funnel);
			failureConnectionIds.add((String) connections.getLast().get("identifier"));
		}

		private Map<String, Object> end(Ref ref) {
			Map<String, Object> end = new LinkedHashMap<>();
			end.put("id", ref.id());
			end.put("type", ref.type());
			end.put("groupId", pgId);
			end.put("name", ref.name());
			end.put("comments", "");
			return end;
		}

		private Map<String, Object> position(int x, int y) {
			Map<String, Object> position = new LinkedHashMap<>();
			position.put("x", x);
			position.put("y", y);
			return position;
		}

		private Map<String, Object> orderedMap(Object... pairs) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (int i = 0; i < pairs.length; i += 2) {
				map.put((String) pairs[i], pairs[i + 1]);
			}
			return map;
		}

		private Map<String, Object> envelope() {
			Map<String, Object> funnelNode = new LinkedHashMap<>();
			funnelNode.put("identifier", funnel.id());
			funnelNode.put("position", position(2000, 1400));
			funnelNode.put("componentType", "FUNNEL");
			funnelNode.put("groupIdentifier", pgId);

			Map<String, Object> contents = new LinkedHashMap<>();
			contents.put("identifier", pgId);
			contents.put("name", plan.slug());
			contents.put("comments", "");
			contents.put("position", position(0, 0));
			contents.put("processGroups", List.of());
			contents.put("remoteProcessGroups", List.of());
			contents.put("processors", processors);
			contents.put("inputPorts", List.of());
			contents.put("outputPorts", List.of());
			contents.put("connections", connections);
			contents.put("labels", List.of());
			contents.put("funnels", List.of(funnelNode));
			contents.put("controllerServices", controllerServices);
			contents.put("defaultFlowFileExpiration", "0 sec");
			contents.put("defaultBackPressureObjectThreshold", 10000);
			contents.put("defaultBackPressureDataSizeThreshold", "1 GB");
			contents.put("scheduledState", "ENABLED");
			contents.put("executionEngine", "INHERITED");
			contents.put("maxConcurrentTasks", 1);
			contents.put("statelessFlowTimeout", "1 min");
			contents.put("flowFileConcurrency", "UNBOUNDED");
			contents.put("flowFileOutboundPolicy", "STREAM_WHEN_AVAILABLE");
			contents.put("componentType", "PROCESS_GROUP");

			Map<String, Object> document = new LinkedHashMap<>();
			document.put("externalControllerServices", orderedMap());
			document.put("flowContents", contents);
			document.put("flowEncodingVersion", "1.0");
			document.put("latest", false);
			document.put("parameterContexts", orderedMap());
			document.put("parameterProviders", orderedMap());
			return document;
		}
	}
}
