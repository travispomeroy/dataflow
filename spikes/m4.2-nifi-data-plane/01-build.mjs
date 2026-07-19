// Builds the m42-data-plane process group on live NiFi via REST, piecewise —
// spike Q1–Q7 topology. Throwaway; the exported definition + findings are the
// deliverable. Ends by printing every processor's validation errors (empty = good).
import { nifi, rootPgId, REV0, env } from './lib.mjs';
import { POSITIONS_ROW, INVESTORS_ROW, ORDERS_ROW, JOIN1_ROW, FEED_ROW } from './schemas.mjs';

const PG_NAME = 'm42-data-plane';
const BASE = 'http://wiremock:8080';
const BUSINESS_DATE = '2026-07-17';
const CLASSES = ['cash', 'derivatives', 'equity', 'fixed_income', 'other'];
const { MINIO_ROOT_USER, MINIO_ROOT_PASSWORD } = env();

const root = await rootPgId();
const existing = (await nifi('GET', `/flow/process-groups/${root}`)).processGroupFlow.flow.processGroups
  .find((g) => g.component.name === PG_NAME);
if (existing) throw new Error(`${PG_NAME} already exists (${existing.id}) — run 05-teardown.mjs first`);

const pg = await nifi('POST', `/process-groups/${root}/process-groups`, {
  revision: REV0, component: { name: PG_NAME, position: { x: 0, y: 0 } },
});
const pgId = pg.id;
console.log(`PG ${PG_NAME} = ${pgId}`);

// ---- controller services -------------------------------------------------
async function service(type, name, properties) {
  const s = await nifi('POST', `/process-groups/${pgId}/controller-services`, {
    revision: REV0, component: { type, name, properties },
  });
  console.log(`  service ${name} = ${s.id}`);
  return s.id;
}
const JSON_READER = 'org.apache.nifi.json.JsonTreeReader';
const JSON_WRITER = 'org.apache.nifi.json.JsonRecordSetWriter';

const pageReader = (schema) => ({
  'Schema Access Strategy': 'schema-text-property', 'Schema Text': schema,
  'Starting Field Strategy': 'NESTED_FIELD', 'Starting Field Name': 'data',
  'Schema Application Strategy': 'SELECTED_PART',
});
const flatReader = (schema) => ({
  'Schema Access Strategy': 'schema-text-property', 'Schema Text': schema,
});
const jsonWriter = (schema) => ({
  'Schema Write Strategy': 'no-schema',
  'Schema Access Strategy': 'schema-text-property', 'Schema Text': schema,
  'Pretty Print JSON': 'false', 'Output Grouping': 'output-array',
});

const svc = {
  posPageReader: await service(JSON_READER, 'positions-page-reader', pageReader(POSITIONS_ROW)),
  invPageReader: await service(JSON_READER, 'investors-page-reader', pageReader(INVESTORS_ROW)),
  ordPageReader: await service(JSON_READER, 'orders-page-reader', pageReader(ORDERS_ROW)),
  posFlatReader: await service(JSON_READER, 'positions-flat-reader', flatReader(POSITIONS_ROW)),
  invFlatReader: await service(JSON_READER, 'investors-flat-reader', flatReader(INVESTORS_ROW)),
  ordFlatReader: await service(JSON_READER, 'orders-flat-reader', flatReader(ORDERS_ROW)),
  join1FlatReader: await service(JSON_READER, 'join1-flat-reader', flatReader(JOIN1_ROW)),
  feedFlatReader: await service(JSON_READER, 'feed-flat-reader', flatReader(FEED_ROW)),
  posJsonWriter: await service(JSON_WRITER, 'positions-json-writer', jsonWriter(POSITIONS_ROW)),
  invJsonWriter: await service(JSON_WRITER, 'investors-json-writer', jsonWriter(INVESTORS_ROW)),
  ordJsonWriter: await service(JSON_WRITER, 'orders-json-writer', jsonWriter(ORDERS_ROW)),
  join1JsonWriter: await service(JSON_WRITER, 'join1-json-writer', jsonWriter(JOIN1_ROW)),
  feedJsonWriter: await service(JSON_WRITER, 'feed-json-writer', jsonWriter(FEED_ROW)),
  // Q6 — the golden byte contract: header, comma, LF, no enclosure, trailing newline
  csvWriter: await service('org.apache.nifi.csv.CSVRecordSetWriter', 'feed-csv-writer', {
    'Schema Write Strategy': 'no-schema',
    'Schema Access Strategy': 'schema-text-property', 'Schema Text': FEED_ROW,
    'CSV Format': 'custom', 'CSV Writer': 'commons-csv',
    'Value Separator': ',', 'Record Separator': '\n',
    'Include Header Line': 'true', 'Quote Mode': 'NONE',
    'Include Trailing Delimiter': 'false', 'Character Set': 'UTF-8',
  }),
  awsCreds: await service(
    'org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService',
    'minio-credentials',
    { 'Access Key ID': MINIO_ROOT_USER, 'Secret Access Key': MINIO_ROOT_PASSWORD },
  ),
};

// ---- processors ----------------------------------------------------------
async function processor(type, name, position, properties, autoTerminate = []) {
  const p = await nifi('POST', `/process-groups/${pgId}/processors`, {
    revision: REV0,
    component: {
      type, name, position,
      config: { properties, autoTerminatedRelationships: autoTerminate },
    },
  });
  console.log(`  processor ${name} = ${p.id}`);
  return p.id;
}
const funnel = (await nifi('POST', `/process-groups/${pgId}/funnels`, {
  revision: REV0, component: { position: { x: 2000, y: 1400 } },
})).id;
console.log(`  failure funnel = ${funnel}`);

async function connect(name, [srcId, srcType], rels, [dstId, dstType]) {
  await nifi('POST', `/process-groups/${pgId}/connections`, {
    revision: REV0,
    component: {
      name,
      source: { id: srcId, groupId: pgId, type: srcType },
      destination: { id: dstId, groupId: pgId, type: dstType },
      selectedRelationships: rels,
    },
  });
}
const P = 'PROCESSOR', F = 'FUNNEL';

// Q1 — sequential cursor loop, one instance per API (spec: three parallel instances)
async function paginationBranch(api, pageSize, x, reader, writer) {
  const y = (i) => ({ x, y: i * 150 });
  const seed = await processor('org.apache.nifi.processors.standard.GenerateFlowFile',
    `${api}: seed page 1`, y(0),
    // Custom Text must be non-empty once the property exists; seed content is
    // never sent anywhere (GET request), only the page attribute matters
    { 'File Size': '0B', 'Custom Text': 'seed', page: '1' });
  const fetch = await processor('org.apache.nifi.processors.standard.InvokeHTTP',
    `${api}: fetch page`, y(1),
    { 'HTTP Method': 'GET', 'HTTP URL': `${BASE}/${api}?page=\${page}&pageSize=${pageSize}` },
    ['Original']);
  const totals = await processor('org.apache.nifi.processors.standard.EvaluateJsonPath',
    `${api}: read totalPages`, y(2),
    { Destination: 'flowfile-attribute', totalPages: '$.totalPages' });
  const frag = await processor('org.apache.nifi.processors.attributes.UpdateAttribute',
    `${api}: fragment attributes`, y(3), {
      'fragment.identifier': api,
      'fragment.index': '${page}',
      'fragment.count': '${totalPages}',
    });
  const route = await processor('org.apache.nifi.processors.standard.RouteOnAttribute',
    `${api}: more pages?`, y(4),
    { next: '${page:lt(${totalPages})}' }, ['unmatched']);
  const incr = await processor('org.apache.nifi.processors.attributes.UpdateAttribute',
    `${api}: next cursor`, { x: x + 220, y: 1 * 150 }, { page: '${page:plus(1)}' });
  const merge = await processor('org.apache.nifi.processors.standard.MergeRecord',
    `${api}: defragment pages`, y(5), {
      'Record Reader': reader, 'Record Writer': writer,
      'Merge Strategy': 'Defragment', 'Maximum Number of Records': '10000',
    }, ['original']);

  await connect(`${api} seed`, [seed, P], ['success'], [fetch, P]);
  await connect(`${api} response`, [fetch, P], ['Response'], [totals, P]);
  await connect(`${api} envelope`, [totals, P], ['matched'], [frag, P]);
  await connect(`${api} to merge`, [frag, P], ['success'], [merge, P]);
  await connect(`${api} to route`, [frag, P], ['success'], [route, P]);
  await connect(`${api} loop back`, [route, P], ['next'], [incr, P]);   // the cycle
  await connect(`${api} next fetch`, [incr, P], ['success'], [fetch, P]);
  await connect(`${api} fetch failures`, [fetch, P], ['Failure', 'Retry', 'No Retry'], [funnel, F]);
  await connect(`${api} path failures`, [totals, P], ['failure', 'unmatched'], [funnel, F]);
  await connect(`${api} merge failures`, [merge, P], ['failure'], [funnel, F]);
  return { seed, merge };
}

const pos = await paginationBranch('positions', 50, 0, svc.posPageReader, svc.posJsonWriter);
const inv = await paginationBranch('investors', 4, 500, svc.invPageReader, svc.invJsonWriter);
const ord = await paginationBranch('orders', 75, 1000, svc.ordPageReader, svc.ordJsonWriter);

// Q3 — ADR-0006 collapse: latest per (Client, symbol), max tradeDate then max orderId
const collapse = await processor('org.apache.nifi.processors.standard.QueryRecord',
  'orders: latest-order collapse', { x: 1000, y: 900 }, {
    'Record Reader': svc.ordFlatReader, 'Record Writer': svc.ordJsonWriter,
    collapsed: `SELECT "investorId", "symbol", "orderId", "side", "quantity", "status", "tradeDate"
FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY "investorId", "symbol" ORDER BY "tradeDate" DESC, "orderId" DESC) AS "rn" FROM FLOWFILE) AS "t"
WHERE "rn" = 1`,
  }, ['original']);

// Q4 — 3-way merge: two chained JoinEnrichment stages (SQL strategy).
// JoinEnrichment pairs flowfiles by ForkEnrichment's attributes; without a fork,
// we mint them ourselves: same enrichment.group.id, roles ORIGINAL/ENRICHMENT.
const tag = (name, pos_, groupId, role) => processor(
  'org.apache.nifi.processors.attributes.UpdateAttribute', name, pos_,
  { 'enrichment.group.id': groupId, 'enrichment.role': role });

const j1Orig = await tag('join1: tag positions ORIGINAL', { x: 0, y: 900 }, 'positions-investors', 'ORIGINAL');
const j1Enrich = await tag('join1: tag investors ENRICHMENT', { x: 500, y: 900 }, 'positions-investors', 'ENRICHMENT');
const join1 = await processor('org.apache.nifi.processors.standard.JoinEnrichment',
  'merge 1: positions x investors', { x: 250, y: 1050 }, {
    'Original Record Reader': svc.posFlatReader,
    'Enrichment Record Reader': svc.invFlatReader,
    'Record Writer': svc.join1JsonWriter,
    'Join Strategy': 'SQL',
    SQL: `SELECT original."investorId" AS "clientId", enrichment."name" AS "clientName", enrichment."advisorGroup" AS "advisorGroup",
original."symbol" AS "symbol", original."assetClass" AS "assetClass", original."quantity" AS "quantity",
original."marketValue" AS "marketValue", original."currency" AS "currency"
FROM original LEFT OUTER JOIN enrichment ON original."investorId" = enrichment."investorId"
ORDER BY original."investorId", original."symbol"`,
  }, ['original']);

const j2Orig = await tag('join2: tag merged ORIGINAL', { x: 250, y: 1200 }, 'merged-orders', 'ORIGINAL');
const j2Enrich = await tag('join2: tag orders ENRICHMENT', { x: 1000, y: 1050 }, 'merged-orders', 'ENRICHMENT');
const join2 = await processor('org.apache.nifi.processors.standard.JoinEnrichment',
  'merge 2: x collapsed orders', { x: 600, y: 1350 }, {
    'Original Record Reader': svc.join1FlatReader,
    'Enrichment Record Reader': svc.ordFlatReader,
    'Record Writer': svc.feedJsonWriter,
    'Join Strategy': 'SQL',
    SQL: `SELECT original."clientId" AS "clientId", original."clientName" AS "clientName", original."advisorGroup" AS "advisorGroup",
original."symbol" AS "symbol", original."assetClass" AS "assetClass", original."quantity" AS "quantity",
original."marketValue" AS "marketValue", original."currency" AS "currency",
enrichment."orderId" AS "orderId", enrichment."side" AS "orderSide", enrichment."quantity" AS "orderQuantity",
enrichment."status" AS "orderStatus", enrichment."tradeDate" AS "tradeDate"
FROM original LEFT OUTER JOIN enrichment
ON original."clientId" = enrichment."investorId" AND original."symbol" = enrichment."symbol"
ORDER BY original."clientId", original."symbol"`,
  }, ['original']);

// Client filter (Deployment-frozen config; not one of the seven questions but
// required for the goldens to byte-match)
const filter = await processor('org.apache.nifi.processors.standard.QueryRecord',
  'client filter', { x: 600, y: 1500 }, {
    'Record Reader': svc.feedFlatReader, 'Record Writer': svc.feedJsonWriter,
    filtered: `SELECT * FROM FLOWFILE WHERE "clientId" IN ('INV-001', 'INV-002', 'INV-003')`,
  }, ['original']);

// Q5 — five-way split: one QueryRecord, five static relationships (+ an
// empty-class probe: 'commodities' matches nothing → header-only file).
// Q6 rides on the writer; ORDER BY (clientId, symbol) is unique post-collapse,
// so it fully determines row order.
const splitSql = (cls) => `SELECT "clientId", "clientName", "advisorGroup", "symbol", "assetClass", "quantity", "marketValue", "currency", "orderId", "orderSide", "orderQuantity", "orderStatus", "tradeDate" FROM FLOWFILE WHERE "assetClass" = '${cls}' ORDER BY "clientId", "symbol"`;
const split = await processor('org.apache.nifi.processors.standard.QueryRecord',
  'asset class split', { x: 600, y: 1650 }, {
    'Record Reader': svc.feedFlatReader, 'Record Writer': svc.csvWriter,
    ...Object.fromEntries(CLASSES.map((c) => [c, splitSql(c)])),
    empty_probe: splitSql('commodities'),
  }, ['original']);

// Q7 — staging write: PutS3Object → MinIO, path-style, endpoint override
const stage = await processor('org.apache.nifi.processors.aws.s3.PutS3Object',
  'stage to MinIO', { x: 600, y: 1950 }, {
    Bucket: 'staging',
    'Object Key': 'm42-positions-feed/${runTag}/${filename}',
    Region: 'us-east-1',
    'AWS Credentials Provider Service': svc.awsCreds,
    'Endpoint Override URL': 'http://minio:9000',
    'Use Path Style Access': 'true',
  }, ['success']);

await connect('positions to tag', [pos.merge, P], ['merged'], [j1Orig, P]);
await connect('investors to tag', [inv.merge, P], ['merged'], [j1Enrich, P]);
await connect('join1 original in', [j1Orig, P], ['success'], [join1, P]);
await connect('join1 enrichment in', [j1Enrich, P], ['success'], [join1, P]);
await connect('orders to collapse', [ord.merge, P], ['merged'], [collapse, P]);
await connect('join1 to tag', [join1, P], ['joined'], [j2Orig, P]);
await connect('collapse to tag', [collapse, P], ['collapsed'], [j2Enrich, P]);
await connect('join2 original in', [j2Orig, P], ['success'], [join2, P]);
await connect('join2 enrichment in', [j2Enrich, P], ['success'], [join2, P]);
await connect('join2 to filter', [join2, P], ['joined'], [filter, P]);
await connect('filter to split', [filter, P], ['filtered'], [split, P]);
await connect('join1 failures', [join1, P], ['failure', 'timeout'], [funnel, F]);
await connect('join2 failures', [join2, P], ['failure', 'timeout'], [funnel, F]);
await connect('collapse failures', [collapse, P], ['failure'], [funnel, F]);
await connect('filter failures', [filter, P], ['failure'], [funnel, F]);
await connect('split failures', [split, P], ['failure'], [funnel, F]);
await connect('stage failures', [stage, P], ['failure'], [funnel, F]);

// File Definition name pattern, Business Date resolved (fixed for the spike)
for (const [i, cls] of [...CLASSES, 'commodities'].entries()) {
  const rel = cls === 'commodities' ? 'empty_probe' : cls;
  const namer = await processor('org.apache.nifi.processors.attributes.UpdateAttribute',
    `filename: ${cls}`, { x: 100 + i * 320, y: 1800 },
    { filename: `positions_${cls}_${BUSINESS_DATE}.csv` });
  await connect(`split ${rel}`, [split, P], [rel], [namer, P]);
  await connect(`${cls} to stage`, [namer, P], ['success'], [stage, P]);
}

// Seeds start DISABLED (M4.1 finding: PG start skips DISABLED; run protocol
// enables + RUN_ONCEs them — no timer race).
for (const seed of [pos.seed, inv.seed, ord.seed]) {
  const rev = (await nifi('GET', `/processors/${seed}`)).revision;
  await nifi('PUT', `/processors/${seed}/run-status`, { revision: rev, state: 'DISABLED' });
}

// Enable all controller services in the PG
await nifi('PUT', `/flow/process-groups/${pgId}/controller-services`, { id: pgId, state: 'ENABLED' });

// Validation sweep: surface misconfiguration now, not at start time
await new Promise((r) => setTimeout(r, 3000));
const procs = (await nifi('GET', `/process-groups/${pgId}/processors`)).processors;
let bad = 0;
for (const p of procs) {
  const errs = p.component.validationErrors ?? [];
  if (p.component.validationStatus !== 'VALID') {
    bad++;
    console.log(`INVALID ${p.component.name} [${p.component.validationStatus}]`);
    for (const e of errs) console.log(`    - ${e}`);
  }
}
console.log(bad === 0 ? 'ALL PROCESSORS VALID' : `${bad} invalid processors`);
