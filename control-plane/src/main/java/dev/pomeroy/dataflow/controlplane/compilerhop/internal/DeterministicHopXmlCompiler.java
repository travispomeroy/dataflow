package dev.pomeroy.dataflow.controlplane.compilerhop.internal;

import dev.pomeroy.dataflow.controlplane.compiler.ApiExtraction;
import dev.pomeroy.dataflow.controlplane.compiler.ClientFilterStep;
import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compiler.Extraction;
import dev.pomeroy.dataflow.controlplane.compiler.OutputFile;
import dev.pomeroy.dataflow.controlplane.compiler.Pagination;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationStyle;
import dev.pomeroy.dataflow.controlplane.compiler.PaginationTermination;
import dev.pomeroy.dataflow.controlplane.compiler.TransformStep;
import dev.pomeroy.dataflow.controlplane.compilerhop.HopArtifact;
import dev.pomeroy.dataflow.controlplane.compilerhop.HopPipelineCompiler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Writes the Hop XML by hand — no XML library, so every byte is owned here and the
 * committed goldens stay a valid comparison (the Kestra compiler's discipline, M1.5).
 * Element shapes are copied from what Hop 2.18.1 itself serializes (the image's
 * bundled sample project and the spike's hand-run pipeline), never invented.
 *
 * <p>The generated page loop is explicit and fully declarative — probe page 1, read
 * {@code totalPages} from the envelope, clone one row per page, URL-from-field REST
 * calls — so a dropped page is impossible by construction and visible in the bytes.
 * Pass-through fields are typed String end-to-end: upstream numeric literals reach
 * the CSVs verbatim, never round-tripped through a double (spike #22).
 *
 * <p>Platform conventions live here, not in the plan: the Staging store (bucket
 * {@code staging} on the compose MinIO, reached as {@code minio://} VFS paths), the
 * local-produce-then-stage split (the Hop 2.18.1 direct-write defect, spike #22),
 * and the fixed sentinel timestamps Hop's file format requires but determinism
 * forbids varying.
 */
@Component
public class DeterministicHopXmlCompiler implements HopPipelineCompiler {

	@Override
	public HopArtifact compile(ExecutionPlan plan) {
		plan.extraction().apis().forEach(DeterministicHopXmlCompiler::requireSupportedPagination);
		return new HopArtifact(plan.slug() + ".hpl", pipelineXml(plan),
				plan.slug() + ".hwf", workflowXml(plan));
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
					("the hop compiler generates page loops only for pageNumber/totalPages"
							+ " pagination; api '%s' declares %s").formatted(api.name(),
									pagination == null ? "none"
											: "%s/%s".formatted(pagination.style(),
													pagination.termination())));
		}
	}

	// -- The pipeline: extraction, merge, transforms, file production ------------

	private String pipelineXml(ExecutionPlan plan) {
		Extraction extraction = plan.extraction();
		List<ApiExtraction> apis = extraction.apis();
		ApiExtraction base = apis.getFirst();
		List<ApiExtraction> joiners = apis.subList(1, apis.size());

		Doc doc = new Doc();
		Map<String, String> branchEnds = new LinkedHashMap<>();
		for (int row = 0; row < apis.size(); row++) {
			ApiExtraction api = apis.get(row);
			branchEnds.put(api.name(),
					extractionBranch(doc, extraction, api, base, joiners, row));
		}

		int mergeRow = apis.size();
		int col = 0;
		String current = branchEnds.get(base.name());
		for (int i = 0; i < joiners.size(); i++) {
			ApiExtraction joiner = joiners.get(i);
			String join = "merge " + joiner.name();
			doc.transform(join, "MergeJoin",
					mergeJoinBody(current, branchEnds.get(joiner.name()), base, joiner),
					col++, mergeRow);
			doc.hop(current, join);
			doc.hop(branchEnds.get(joiner.name()), join);
			current = join;
			if (i + 1 < joiners.size()) {
				ApiExtraction next = joiners.get(i + 1);
				String resort = "sort for the %s merge".formatted(next.name());
				doc.transform(resort, "SortRows",
						sortBody(ascending(leftJoinKeys(base, next))), col++, mergeRow);
				doc.hop(current, resort);
				current = resort;
			}
		}

		for (TransformStep step : plan.transforms()) {
			current = transformStep(doc, step, current, col, mergeRow);
			col += 2;
		}

		String context = "resolve run context";
		doc.copyingTransform(context, "GetVariable", runContextBody(), col, mergeRow);
		doc.hop(current, context);

		for (int i = 0; i < plan.files().size(); i++) {
			fileChain(doc, plan.files().get(i), context, mergeRow + 1 + i);
		}

		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<pipeline>
				  <info>
				    <name>%s</name>
				    <name_sync_with_filename>Y</name_sync_with_filename>
				    <description>The %s Dataflow's engine pipeline, compiled from its Execution Plan</description>
				    <extended_description/>
				    <pipeline_version/>
				    <pipeline_type>Normal</pipeline_type>
				    <pipeline_status>0</pipeline_status>
				%s    <capture_transform_performance>N</capture_transform_performance>
				    <transform_performance_capturing_delay>1000</transform_performance_capturing_delay>
				    <transform_performance_capturing_size_limit>100</transform_performance_capturing_size_limit>
				    <created_user>-</created_user>
				    <created_date>1970/01/01 00:00:00.000</created_date>
				    <modified_user>-</modified_user>
				    <modified_date>1970/01/01 00:00:00.000</modified_date>
				  </info>
				  <notepads/>
				  <order>
				%s  </order>
				%s  <transform_error_handling>
				  </transform_error_handling>
				  <attributes/>
				</pipeline>
				""".formatted(xml(plan.slug()), xml(plan.slug()), parameters(plan, "    "),
				doc.hops, doc.transforms);
	}

	/**
	 * One upstream API's page-loop extraction: probe page 1 for the envelope's
	 * {@code totalPages}, clone one row per page (the probe row itself is numbered 0
	 * and dropped), fetch every page with a URL-from-field REST call, parse
	 * {@code $.data[*]} with every pass-through field typed String, project away the
	 * loop plumbing, and sort for the merge. A collapsing API (ADR-0006) additionally
	 * reduces to the latest record per join key before the merge: the latest-by sort
	 * runs descending, so Unique keeps exactly the latest.
	 */
	private String extractionBranch(Doc doc, Extraction extraction, ApiExtraction api,
			ApiExtraction base, List<ApiExtraction> joiners, int row) {
		boolean isBase = api == base;
		String name = api.name();
		String pageBase = "%s%s?page=".formatted(extraction.baseUrl(), api.path());
		String pageSuffix = "&pageSize=%d".formatted(api.pagination().pageSize());
		Map<String, String> extracted = extractedFields(extraction, api, base, joiners);

		String seed = name + ": seed page 1";
		String probe = name + ": probe page 1";
		String read = name + ": read totalPages";
		String clone = name + ": one row per page";
		String number = name + ": number the pages";
		String keep = name + ": keep real pages";
		String discard = name + ": discard the probe row";
		String url = name + ": page url";
		String fetch = name + ": fetch every page";
		String parse = name + ": parse rows";
		String project = name + ": project fields";
		String sort = name + ": sort for merge";

		doc.transform(seed, "RowGenerator", rowGeneratorBody(pageBase, pageSuffix), 0, row);
		doc.transform(probe, "Rest", staticRestBody(pageBase + "1" + pageSuffix), 1, row);
		doc.transform(read, "JsonInput", totalPagesBody(), 2, row);
		doc.transform(clone, "CloneRow", cloneRowBody(), 3, row);
		doc.transform(number, "Sequence", sequenceBody(name), 4, row);
		doc.transform(keep, "FilterRows", pageFilterBody(url, discard), 5, row);
		doc.offsetTransform(discard, "Dummy", "", 5, row);
		doc.transform(url, "ConcatFields", pageUrlBody(), 6, row);
		doc.transform(fetch, "Rest", fieldRestBody(), 7, row);
		doc.transform(parse, "JsonInput", parseRowsBody(extracted), 8, row);
		doc.transform(project, "SelectValues", selectBody(List.copyOf(extracted.keySet())), 9, row);
		doc.chain(seed, probe, read, clone, number, keep, url, fetch, parse, project);
		doc.hop(keep, discard);

		List<SortField> sortFields = new ArrayList<>();
		if (isBase) {
			if (joiners.isEmpty()) {
				return project;
			}
			sortFields.addAll(ascending(leftJoinKeys(base, joiners.getFirst())));
		}
		else {
			sortFields.addAll(ascending(rightJoinKeys(api)));
			if (api.collapse() != null) {
				api.collapse().latestBy().forEach(
						field -> sortFields.add(descending(latestByField(api, field))));
			}
		}
		doc.transform(sort, "SortRows", sortBody(sortFields), 10, row);
		doc.hop(project, sort);
		if (isBase || api.collapse() == null) {
			return sort;
		}

		String latest = name + ": keep the latest per key";
		doc.transform(latest, "Unique", uniqueBody(rightJoinKeys(api)), 11, row);
		doc.hop(sort, latest);
		return latest;
	}

	/**
	 * What one API's JsonInput extracts, in emission order: field name → upstream
	 * column. The base API adds the merge key and every joiner's join columns (under
	 * their upstream names) ahead of its projection; a joining API extracts its join
	 * columns under {@code api_column} names, so no field name ever collides across
	 * the merged branches. Projection entries follow, upstream column → logical name.
	 */
	private Map<String, String> extractedFields(Extraction extraction, ApiExtraction api,
			ApiExtraction base, List<ApiExtraction> joiners) {
		Map<String, String> fields = new LinkedHashMap<>();
		if (api == base) {
			LinkedHashSet<String> keys = new LinkedHashSet<>();
			keys.add(extraction.merge().key());
			joiners.forEach(joiner -> keys.addAll(joiner.joinOn()));
			keys.removeAll(api.fields().keySet());
			keys.forEach(column -> fields.put(column, column));
		}
		else {
			api.joinOn().forEach(column -> fields.put(joinFieldName(api, column), column));
		}
		api.fields().forEach((upstream, logical) -> fields.put(logical, upstream));
		return fields;
	}

	/** A joining API's join columns live under collision-proof {@code api_column} names. */
	private static String joinFieldName(ApiExtraction api, String column) {
		return api.name() + "_" + column;
	}

	/** The merged stream's name for a join column: the base's projection wins, else raw. */
	private static List<String> leftJoinKeys(ApiExtraction base, ApiExtraction joiner) {
		return joiner.joinOn().stream()
				.map(column -> base.fields().getOrDefault(column, column)).toList();
	}

	private static List<String> rightJoinKeys(ApiExtraction api) {
		return api.joinOn().stream().map(column -> joinFieldName(api, column)).toList();
	}

	/** A latest-by column as it exists on the extracted stream — projected or join key. */
	private static String latestByField(ApiExtraction api, String column) {
		if (api.fields().containsKey(column)) {
			return api.fields().get(column);
		}
		if (api.joinOn().contains(column)) {
			return joinFieldName(api, column);
		}
		throw new IllegalArgumentException(
				"collapse latestBy column '%s' is not extracted from api '%s'"
						.formatted(column, api.name()));
	}

	private String transformStep(Doc doc, TransformStep step, String previous, int col,
			int row) {
		return switch (step) {
			case ClientFilterStep filter -> {
				String name = "filter by Clients";
				String selected = "selected Clients";
				String discard = "other Clients";
				doc.transform(name, "FilterRows", inListFilterBody(selected, discard,
						filter.field(), filter.clientIds()), col, row);
				doc.transform(selected, "Dummy", "", col + 1, row);
				doc.offsetTransform(discard, "Dummy", "", col, row);
				doc.hop(previous, name);
				doc.hop(name, selected);
				doc.hop(name, discard);
				yield selected;
			}
		};
	}

	/**
	 * One File Definition's production: sort by the file's full column projection (a
	 * total order, so bytes never depend on upstream arrival order; for the Positions
	 * Feed this is the contract's {@code (clientId, symbol)} order because the
	 * columns between them are constant per Client), resolve the name pattern into a
	 * per-row target file name, and write field-driven CSVs — one file per
	 * {@code splitBy} value, resolved entirely at run time. CSV contract: header of
	 * plan field names, comma, LF, trailing newline, no enclosure, String fields
	 * throughout.
	 */
	private void fileChain(Doc doc, OutputFile file, String previous, int row) {
		String id = file.fileDefinitionId();
		String sort = id + ": sort delivered rows";
		String parts = id + ": file name parts";
		String fileName = id + ": file name";
		String write = "write " + id;

		List<String> literals = new ArrayList<>();
		List<String> concatFields = fileNameFields(file, literals);

		doc.transform(sort, "SortRows", sortBody(ascending(file.columns())), 0, row);
		doc.transform(parts, "Constant", constantsBody(literals), 1, row);
		doc.transform(fileName, "ConcatFields",
				concatBody("targetFileName", concatFields, null), 2, row);
		doc.transform(write, "TextFileOutput", textFileOutputBody(file.columns()), 3, row);
		doc.hop(previous, sort);
		doc.chain(sort, parts, fileName, write);
	}

	/**
	 * The name pattern, resolved into a concat field list: literal runs become
	 * constant fields ({@code namePart1}…, collected into {@code literals}),
	 * {@code {businessDate}} is the run-context variable field, and the
	 * {@code splitBy} token is the row's own field — the fan-out into one file per
	 * value. The produced name is rooted at {@code OUT_DIR}, so the leading slash
	 * joins the first literal.
	 */
	private List<String> fileNameFields(OutputFile file, List<String> literals) {
		List<String> fields = new ArrayList<>();
		fields.add("outDir");
		StringBuilder literal = new StringBuilder("/");
		for (String segment : file.namePattern().split("(?=\\{)|(?<=\\})")) {
			if (segment.startsWith("{") && segment.endsWith("}")) {
				String token = segment.substring(1, segment.length() - 1);
				if (!literal.isEmpty()) {
					literals.add(literal.toString());
					fields.add("namePart" + literals.size());
					literal.setLength(0);
				}
				if (token.equals("businessDate")) {
					fields.add("businessDate");
				}
				else if (token.equals(file.splitBy())) {
					fields.add(file.splitBy());
				}
				else {
					throw new IllegalArgumentException(
							"unknown file name token '{%s}' in pattern '%s'"
									.formatted(token, file.namePattern()));
				}
			}
			else {
				literal.append(segment);
			}
		}
		if (!literal.isEmpty()) {
			literals.add(literal.toString());
			fields.add("namePart" + literals.size());
		}
		return fields;
	}

	// -- Transform bodies, shaped exactly as Hop 2.18.1 serializes them ----------

	private String rowGeneratorBody(String pageBase, String pageSuffix) {
		return """
				    <fields>
				%s%s    </fields>
				    <interval_in_ms>5000</interval_in_ms>
				    <last_time_field>FiveSecondsAgo</last_time_field>
				    <never_ending>N</never_ending>
				    <limit>1</limit>
				    <row_time_field>now</row_time_field>
				""".formatted(generatedField("pageUrlBase", pageBase),
				generatedField("pageUrlSuffix", pageSuffix));
	}

	private String generatedField(String name, String value) {
		return """
				      <field>
				        <currency/>
				        <decimal/>
				        <format/>
				        <group/>
				        <length>-1</length>
				        <name>%s</name>
				        <precision>-1</precision>
				        <set_empty_string>N</set_empty_string>
				        <type>String</type>
				        <nullif>%s</nullif>
				      </field>
				""".formatted(xml(name), xml(value));
	}

	private String staticRestBody(String url) {
		return restBody("<url>%s</url>".formatted(xml(url)), "N", "", "envelope");
	}

	private String fieldRestBody() {
		return restBody("<url/>", "Y", "pageUrl", "pageBody");
	}

	private String restBody(String urlElement, String urlInField, String urlField,
			String resultField) {
		return """
				    <applicationType>TEXT PLAIN</applicationType>
				    <method>GET</method>
				    %s
				    <urlInField>%s</urlInField>
				    <dynamicMethod>N</dynamicMethod>
				    <methodFieldName/>
				    <urlField>%s</urlField>
				    <bodyField/>
				    <httpLogin/>
				    <httpPassword>Encrypted </httpPassword>
				    <proxyHost/>
				    <proxyPort/>
				    <preemptive>N</preemptive>
				    <trustStoreFile/>
				    <trustStorePassword>Encrypted </trustStorePassword>
				    <ignoreSsl>N</ignoreSsl>
				    <headers>
				      </headers>
				    <parameters>
				      </parameters>
				    <matrixParameters>
				      </matrixParameters>
				    <result>
				      <name>%s</name>
				      <code/>
				      <response_time/>
				      <response_header/>
				    </result>
				""".formatted(urlElement, urlInField, xml(urlField), xml(resultField));
	}

	private String totalPagesBody() {
		return jsonInputBody("envelope",
				jsonField("totalPages", "$.totalPages", "Integer"));
	}

	private String parseRowsBody(Map<String, String> extracted) {
		StringBuilder fields = new StringBuilder();
		extracted.forEach((name, upstream) -> fields
				.append(jsonField(name, "$.data[*]." + upstream, "String")));
		return jsonInputBody("pageBody", fields.toString());
	}

	private String jsonInputBody(String sourceField, String fields) {
		return """
				    <include>N</include>
				    <include_field/>
				    <rownum>N</rownum>
				    <addresultfile>N</addresultfile>
				    <readurl>N</readurl>
				    <removeSourceField>Y</removeSourceField>
				    <IsIgnoreEmptyFile>N</IsIgnoreEmptyFile>
				    <doNotFailIfNoFile>N</doNotFailIfNoFile>
				    <ignoreMissingPath>N</ignoreMissingPath>
				    <defaultPathLeafToNull>N</defaultPathLeafToNull>
				    <rownum_field/>
				    <file>
				      <name/>
				      <filemask/>
				      <exclude_filemask/>
				      <file_required>N</file_required>
				      <include_subfolders>N</include_subfolders>
				    </file>
				    <fields>
				%s    </fields>
				    <limit>0</limit>
				    <IsInFields>Y</IsInFields>
				    <IsAFile>N</IsAFile>
				    <valueField>%s</valueField>
				    <shortFileFieldName/>
				    <pathFieldName/>
				    <hiddenFieldName/>
				    <lastModificationTimeFieldName/>
				    <uriNameFieldName/>
				    <rootUriNameFieldName/>
				    <extensionFieldName/>
				    <sizeFieldName/>
				""".formatted(fields, xml(sourceField));
	}

	private String jsonField(String name, String path, String type) {
		return """
				      <field>
				        <name>%s</name>
				        <path>%s</path>
				        <type>%s</type>
				        <format/>
				        <currency/>
				        <decimal/>
				        <group/>
				        <length>-1</length>
				        <precision>-1</precision>
				        <trim_type>none</trim_type>
				        <repeat>N</repeat>
				      </field>
				""".formatted(xml(name), xml(path), type);
	}

	private String cloneRowBody() {
		return """
				    <nrclones>0</nrclones>
				    <addcloneflag>N</addcloneflag>
				    <cloneflagfield/>
				    <nrcloneinfield>Y</nrcloneinfield>
				    <nrclonefield>totalPages</nrclonefield>
				    <addclonenum>N</addclonenum>
				    <clonenumfield/>
				""";
	}

	/**
	 * Pages number from 0 so the probe's own row is the one the filter drops. The
	 * counter is named per API: an unnamed counter is one pipeline-global sequence,
	 * and concurrent branches would interleave their page numbers (observed live).
	 */
	private String sequenceBody(String apiName) {
		return """
				    <counter_name>%s_page</counter_name>
				    <use_counter>Y</use_counter>
				    <use_database>N</use_database>
				    <increment_by>1</increment_by>
				    <max_value>999999999</max_value>
				    <schema/>
				    <seqname>SEQ_</seqname>
				    <start_at>0</start_at>
				    <valuename>page</valuename>
				""".formatted(xml(apiName));
	}

	private String pageFilterBody(String trueTarget, String falseTarget) {
		return filterBody(trueTarget, falseTarget, "page", "&gt;", """
				        <value>
				          <name>constant</name>
				          <type>Integer</type>
				          <text>0</text>
				          <length>-1</length>
				          <precision>0</precision>
				          <isnull>N</isnull>
				          <mask>####0;-####0</mask>
				        </value>
				""");
	}

	private String inListFilterBody(String trueTarget, String falseTarget, String field,
			List<String> values) {
		return filterBody(trueTarget, falseTarget, field, "IN LIST", """
				        <value>
				          <name>constant</name>
				          <type>String</type>
				          <text>%s</text>
				          <length>-1</length>
				          <precision>-1</precision>
				          <isnull>N</isnull>
				        </value>
				""".formatted(xml(String.join(";", values))));
	}

	/**
	 * Explicit true/false targets, always: without them Hop's FilterRows passes every
	 * row through untouched (observed live on 2.18.1), so the "filter" would silently
	 * become a no-op. The false target is a Dummy sink.
	 */
	private String filterBody(String trueTarget, String falseTarget, String leftField,
			String function, String value) {
		return """
				    <send_true_to>%s</send_true_to>
				    <send_false_to>%s</send_false_to>
				    <compare>
				      <condition>
				        <negated>N</negated>
				        <leftvalue>%s</leftvalue>
				        <function>%s</function>
				        <rightvalue/>
				%s      </condition>
				    </compare>
				""".formatted(xml(trueTarget), xml(falseTarget), xml(leftField), function,
				value);
	}

	private String pageUrlBody() {
		return concatBody("pageUrl", List.of("pageUrlBase", "page", "pageUrlSuffix"),
				"page");
	}

	/**
	 * Concatenation with an empty separator and no enclosure; {@code integerField}
	 * (the page loop's counter) formats as a bare number, every other input is a
	 * String passed through untouched.
	 */
	private String concatBody(String target, List<String> fields, String integerField) {
		StringBuilder fieldElements = new StringBuilder();
		for (String field : fields) {
			boolean integer = field.equals(integerField);
			fieldElements.append("""
					      <field>
					        <currency/>
					        <decimal/>
					        <format>%s</format>
					        <group/>
					        <length>-1</length>
					        <name>%s</name>
					        <nullif/>
					        <precision>-1</precision>
					        <trim_type>none</trim_type>
					        <type>%s</type>
					      </field>
					""".formatted(integer ? "0" : "", xml(field),
					integer ? "Integer" : "String"));
		}
		return """
				    <ConcatFields>
				      <removeSelectedFields>N</removeSelectedFields>
				      <targetFieldLength>0</targetFieldLength>
				      <targetFieldName>%s</targetFieldName>
				    </ConcatFields>
				    <enclosure/>
				    <fields>
				%s    </fields>
				    <separator/>
				""".formatted(xml(target), fieldElements);
	}

	private String selectBody(List<String> names) {
		StringBuilder fields = new StringBuilder();
		for (String name : names) {
			fields.append("""
					      <field>
					        <name>%s</name>
					        <rename/>
					      </field>
					""".formatted(xml(name)));
		}
		return """
				    <fields>
				%s      <select_unspecified>N</select_unspecified>
				    </fields>
				""".formatted(fields);
	}

	private record SortField(String name, boolean ascending) {
	}

	private static List<SortField> ascending(List<String> fields) {
		return fields.stream().map(field -> new SortField(field, true)).toList();
	}

	private static SortField descending(String field) {
		return new SortField(field, false);
	}

	/**
	 * Case-sensitive byte order on every key: the delivered order must match the
	 * independent oracle's plain string comparison, not a collation.
	 */
	private String sortBody(List<SortField> sortFields) {
		StringBuilder fields = new StringBuilder();
		for (SortField field : sortFields) {
			fields.append("""
					      <field>
					        <name>%s</name>
					        <ascending>%s</ascending>
					        <case_sensitive>Y</case_sensitive>
					        <collator_enabled>N</collator_enabled>
					        <collator_strength>0</collator_strength>
					        <presorted>N</presorted>
					      </field>
					""".formatted(xml(field.name()), field.ascending() ? "Y" : "N"));
		}
		return """
				    <directory>${java.io.tmpdir}</directory>
				    <prefix>out</prefix>
				    <sort_size>5000</sort_size>
				    <free_memory/>
				    <compress>N</compress>
				    <compress_variable/>
				    <unique_rows>N</unique_rows>
				    <fields>
				%s    </fields>
				""".formatted(fields);
	}

	private String uniqueBody(List<String> fields) {
		StringBuilder fieldElements = new StringBuilder();
		for (String field : fields) {
			fieldElements.append("""
					      <field>
					        <name>%s</name>
					        <case_insensitive>N</case_insensitive>
					      </field>
					""".formatted(xml(field)));
		}
		return """
				    <count_rows>N</count_rows>
				    <count_field/>
				    <reject_duplicate_row>N</reject_duplicate_row>
				    <error_description/>
				    <fields>
				%s    </fields>
				""".formatted(fieldElements);
	}

	private String mergeJoinBody(String leftTransform, String rightTransform,
			ApiExtraction base, ApiExtraction joiner) {
		StringBuilder keys1 = new StringBuilder();
		leftJoinKeys(base, joiner)
				.forEach(key -> keys1.append("      <key>%s</key>\n".formatted(xml(key))));
		StringBuilder keys2 = new StringBuilder();
		rightJoinKeys(joiner)
				.forEach(key -> keys2.append("      <key>%s</key>\n".formatted(xml(key))));
		return """
				    <join_type>LEFT OUTER</join_type>
				    <keys_1>
				%s    </keys_1>
				    <keys_2>
				%s    </keys_2>
				    <transform1>%s</transform1>
				    <transform2>%s</transform2>
				""".formatted(keys1, keys2, xml(leftTransform), xml(rightTransform));
	}

	private String runContextBody() {
		return """
				    <fields>
				%s%s    </fields>
				""".formatted(variableField("businessDate", "${BUSINESS_DATE}"),
				variableField("outDir", "${OUT_DIR}"));
	}

	private String variableField(String name, String variable) {
		return """
				      <field>
				        <name>%s</name>
				        <variable>%s</variable>
				        <type>String</type>
				        <format/>
				        <currency/>
				        <decimal/>
				        <group/>
				        <length>-1</length>
				        <precision>-1</precision>
				        <trim_type>none</trim_type>
				      </field>
				""".formatted(xml(name), xml(variable));
	}

	private String constantsBody(List<String> literals) {
		StringBuilder fields = new StringBuilder();
		for (int i = 0; i < literals.size(); i++) {
			fields.append(generatedField("namePart" + (i + 1), literals.get(i)));
		}
		return """
				    <fields>
				%s    </fields>
				""".formatted(fields);
	}

	private String textFileOutputBody(List<String> columns) {
		StringBuilder fields = new StringBuilder();
		for (String column : columns) {
			fields.append("""
					      <field>
					        <name>%s</name>
					        <type>String</type>
					        <format/>
					        <currency/>
					        <decimal/>
					        <group/>
					        <nullif/>
					        <trim_type>none</trim_type>
					        <length>-1</length>
					        <precision>-1</precision>
					      </field>
					""".formatted(xml(column)));
		}
		return """
				    <separator>,</separator>
				    <enclosure/>
				    <enclosure_forced>N</enclosure_forced>
				    <enclosure_fix_disabled>N</enclosure_fix_disabled>
				    <header>Y</header>
				    <footer>N</footer>
				    <format>Unix</format>
				    <compression>None</compression>
				    <encoding>UTF-8</encoding>
				    <endedLine/>
				    <fileNameInField>Y</fileNameInField>
				    <fileNameField>targetFileName</fileNameField>
				    <create_parent_folder>Y</create_parent_folder>
				    <file>
				      <name/>
				      <servlet_output>N</servlet_output>
				      <do_not_open_new_file_init>Y</do_not_open_new_file_init>
				      <extention/>
				      <append>N</append>
				      <split>N</split>
				      <haspartno>N</haspartno>
				      <add_date>N</add_date>
				      <add_time>N</add_time>
				      <SpecifyFormat>N</SpecifyFormat>
				      <date_time_format/>
				      <add_to_result_filenames>N</add_to_result_filenames>
				      <pad>N</pad>
				      <fast_dump>N</fast_dump>
				      <splitevery/>
				    </file>
				    <fields>
				%s    </fields>
				""".formatted(fields);
	}

	// -- The workflow: run the pipeline, then stage what it produced -------------

	/**
	 * The staging write is a workflow Copy Files action — a single clean VFS stream
	 * per file, byte-identical where the pipeline's direct {@code minio://} output
	 * truncates (spike #22). The destination is the platform Staging convention:
	 * bucket {@code staging}, the plan's path with {@code {runId}} late-bound to the
	 * {@code RUN_ID} parameter.
	 */
	private String workflowXml(ExecutionPlan plan) {
		String pathConvention = plan.staging().pathConvention();
		if (pathConvention.endsWith("/")) {
			pathConvention = pathConvention.substring(0, pathConvention.length() - 1);
		}
		String destination = "minio://staging/"
				+ pathConvention.replace("{runId}", "${RUN_ID}");
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<workflow>
				  <name>%s</name>
				  <name_sync_with_filename>Y</name_sync_with_filename>
				  <description>Runs the %s pipeline and stages its files to MinIO (ADR-0001: engines write to Staging)</description>
				  <extended_description/>
				  <workflow_version/>
				  <created_user>-</created_user>
				  <created_date>1970/01/01 00:00:00.000</created_date>
				  <modified_user>-</modified_user>
				  <modified_date>1970/01/01 00:00:00.000</modified_date>
				%s  <actions>
				    <action>
				      <name>Start</name>
				      <description/>
				      <type>SPECIAL</type>
				      <attributes/>
				      <DayOfMonth>1</DayOfMonth>
				      <doNotWaitOnFirstExecution>N</doNotWaitOnFirstExecution>
				      <hour>12</hour>
				      <intervalMinutes>60</intervalMinutes>
				      <intervalSeconds>0</intervalSeconds>
				      <minutes>0</minutes>
				      <repeat>N</repeat>
				      <schedulerType>0</schedulerType>
				      <weekDay>1</weekDay>
				      <parallel>N</parallel>
				      <xloc>96</xloc>
				      <yloc>96</yloc>
				      <attributes_hac/>
				    </action>
				    <action>
				      <name>produce the files</name>
				      <description/>
				      <type>PIPELINE</type>
				      <attributes/>
				      <filename>${Internal.Entry.Current.Folder}/%s</filename>
				      <params_from_previous>N</params_from_previous>
				      <exec_per_row>N</exec_per_row>
				      <clear_rows>N</clear_rows>
				      <clear_files>N</clear_files>
				      <set_logfile>N</set_logfile>
				      <logfile/>
				      <logext/>
				      <add_date>N</add_date>
				      <add_time>N</add_time>
				      <loglevel>Basic</loglevel>
				      <set_append_logfile>N</set_append_logfile>
				      <wait_until_finished>Y</wait_until_finished>
				      <follow_abort_remote>N</follow_abort_remote>
				      <create_parent_folder>N</create_parent_folder>
				      <run_configuration>local</run_configuration>
				      <parameters>
				        <pass_all_parameters>Y</pass_all_parameters>
				      </parameters>
				      <parallel>N</parallel>
				      <xloc>272</xloc>
				      <yloc>96</yloc>
				      <attributes_hac/>
				    </action>
				    <action>
				      <name>stage the files</name>
				      <description/>
				      <type>COPY_FILES</type>
				      <attributes/>
				      <copy_empty_folders>N</copy_empty_folders>
				      <arg_from_previous>N</arg_from_previous>
				      <overwrite_files>Y</overwrite_files>
				      <include_subfolders>N</include_subfolders>
				      <remove_source_files>N</remove_source_files>
				      <add_result_filesname>N</add_result_filesname>
				      <destination_is_a_file>N</destination_is_a_file>
				      <create_destination_folder>Y</create_destination_folder>
				      <fields>
				        <field>
				          <source_filefolder>${OUT_DIR}</source_filefolder>
				          <destination_filefolder>%s</destination_filefolder>
				          <wildcard>.*\\.csv</wildcard>
				        </field>
				      </fields>
				      <parallel>N</parallel>
				      <xloc>448</xloc>
				      <yloc>96</yloc>
				      <attributes_hac/>
				    </action>
				  </actions>
				  <hops>
				    <hop>
				      <from>Start</from>
				      <to>produce the files</to>
				      <enabled>Y</enabled>
				      <evaluation>Y</evaluation>
				      <unconditional>Y</unconditional>
				    </hop>
				    <hop>
				      <from>produce the files</from>
				      <to>stage the files</to>
				      <enabled>Y</enabled>
				      <evaluation>Y</evaluation>
				      <unconditional>N</unconditional>
				    </hop>
				  </hops>
				  <notepads/>
				  <attributes/>
				</workflow>
				""".formatted(xml(plan.slug()), xml(plan.slug()), parameters(plan, "  "),
				xml(plan.slug() + ".hpl"), xml(destination));
	}

	/**
	 * The artifact's parameter contract, identical on both files (the workflow passes
	 * all parameters down). Alphabetical, and only {@code OUT_DIR} carries a default:
	 * a Run must always say which Run it is and which Business Date it is as-of.
	 */
	private String parameters(ExecutionPlan plan, String indent) {
		return """
				<parameters>
				  <parameter>
				    <name>BUSINESS_DATE</name>
				    <default_value/>
				    <description>The Business Date the produced files are as-of; resolved into every file name</description>
				  </parameter>
				  <parameter>
				    <name>OUT_DIR</name>
				    <default_value>/tmp/%s-out</default_value>
				    <description>Local folder the files are produced into before staging (direct minio:// output is broken in Hop 2.18.1)</description>
				  </parameter>
				  <parameter>
				    <name>RUN_ID</name>
				    <default_value/>
				    <description>The Run identifier (the Kestra execution id); names the staging folder</description>
				  </parameter>
				</parameters>
				""".formatted(xml(plan.slug())).indent(indent.length());
	}

	/** Minimal escaping for text nodes; the compiler never emits XML attributes. */
	private static String xml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * Accumulates the pipeline's transforms and hops in emission order, and lays
	 * transforms out on a deterministic grid (one row per extraction branch, then the
	 * merge row, then one row per file) so the canvas stays readable without a single
	 * nondeterministic coordinate.
	 */
	private static final class Doc {

		final StringBuilder transforms = new StringBuilder();

		final StringBuilder hops = new StringBuilder();

		void transform(String name, String type, String body, int col, int row) {
			transform(name, type, body, col, row, "Y", 0);
		}

		/** Copy rows to every output hop instead of round-robin distribution. */
		void copyingTransform(String name, String type, String body, int col, int row) {
			transform(name, type, body, col, row, "N", 0);
		}

		/** Nudged half a row down: a filter's discard sink, out of the main line. */
		void offsetTransform(String name, String type, String body, int col, int row) {
			transform(name, type, body, col, row, "Y", 64);
		}

		private void transform(String name, String type, String body, int col, int row,
				String distribute, int yOffset) {
			transforms.append("""
					  <transform>
					    <name>%s</name>
					    <type>%s</type>
					    <description/>
					    <distribute>%s</distribute>
					    <custom_distribution/>
					    <copies>1</copies>
					    <partitioning>
					      <method>none</method>
					      <schema_name/>
					    </partitioning>
					%s    <attributes/>
					    <GUI>
					      <xloc>%d</xloc>
					      <yloc>%d</yloc>
					    </GUI>
					  </transform>
					""".formatted(xml(name), type, distribute, body, 96 + col * 176,
					96 + row * 128 + yOffset));
		}

		void hop(String from, String to) {
			hops.append("""
					    <hop>
					      <from>%s</from>
					      <to>%s</to>
					      <enabled>Y</enabled>
					    </hop>
					""".formatted(xml(from), xml(to)));
		}

		void chain(String... names) {
			for (int i = 1; i < names.length; i++) {
				hop(names[i - 1], names[i]);
			}
		}
	}
}
