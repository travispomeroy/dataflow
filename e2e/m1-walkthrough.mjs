// M1 REST walkthrough — the product-surface stage of e2e/m1-gates.sh.
// Drives one Dataflow through its whole lifecycle against the live control plane
// (localhost:8085) and live Kestra, asserting only external surfaces: API
// responses, Kestra API state, and the committed golden Execution Plan.
// Sequential by design — each step depends on the state the previous one built,
// so the first failure aborts the walk (unlike smoke.mjs's independent checks).
// Node built-ins only; Kestra credentials come from infra/.env via --env-file.

import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const env = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`missing env var ${name} — run via e2e/m1-gates.sh`);
  return value;
};

const api = 'http://localhost:8085/api';
// v1.x API paths carry the tenant; OSS single-tenant is always "main".
// Flow identity is stable (ADR: one flow per Dataflow): namespace "dataflow",
// flow id = the immutable slug minted from the name "Positions Feed".
const kestraFlow = 'http://localhost:8080/api/v1/main/flows/dataflow/positions-feed';
const kestraAuth = () =>
  `Basic ${Buffer.from(`${env('KESTRA_UI_USER')}:${env('KESTRA_UI_PASSWORD')}`).toString('base64')}`;

const readJson = async (...parts) =>
  JSON.parse(await readFile(join(import.meta.dirname, ...parts), 'utf8'));

// Canonical form for deep equality: objects with sorted keys, then stringify
// (same semantics as smoke.mjs) — nulls are significant, so a field regressing
// to an explicit null cannot slip past the golden comparison
const canonical = (value) =>
  JSON.stringify(value, (_key, v) =>
    v !== null && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
      : v,
  );

// Variant for half-built configs only: the API serializes absent optional
// fields (schedule, operator fields) as explicit nulls the document omits
const canonicalDroppingNulls = (value) =>
  JSON.stringify(value, (_key, v) =>
    v !== null && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(
          Object.entries(v)
            .filter(([, val]) => val !== null)
            .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
        )
      : v,
  );

const assert = (cond, message) => {
  if (!cond) throw new Error(message);
};

// Every call returns {status, location, body}; body JSON-parsed when possible
const call = async (method, url, { body, auth } = {}) => {
  const res = await fetch(url, {
    method,
    headers: {
      ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
      ...(auth ? { authorization: auth } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  return { status: res.status, location: res.headers.get('location'), body: parsed };
};

const expectStatus = (res, want) =>
  assert(
    res.status === want,
    `expected ${want}, got ${res.status}: ${JSON.stringify(res.body)?.slice(0, 300)}`,
  );

const step = async (name, fn) => {
  try {
    const result = await fn();
    console.log(`ok    ${name}`);
    return result;
  } catch (err) {
    console.error(`FAIL  ${name} — ${err.message}`);
    process.exit(1);
  }
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const canonicalConfig = await readJson('canonical', 'positions-feed.config.json');
const goldenPlan = await readJson('golden', 'positions-feed.plan.json');

// The Business Date the run is pinned to (spec #19): inside the fixtures'
// trade-date window, so the delivered file names — and with them the run
// record's delivered-files array — are fully golden.
const businessDate = '2026-07-17';

// What the Run record must say was shipped (M2.7): name + data-row count per
// delivered file, sorted by name, computed here from the committed golden CSVs —
// the same independent-oracle provenance the delivered bytes have. Lines minus
// header; the CSV contract guarantees a trailing newline, hence the extra -1.
const goldenDeliveredFiles = await (async () => {
  const dir = join(import.meta.dirname, 'golden', 'delivered');
  const names = (await readdir(dir)).sort();
  return Promise.all(
    names.map(async (name) => ({
      name,
      records: (await readFile(join(dir, name), 'utf8')).split('\n').length - 2,
    })),
  );
})();

// Structurally fine, semantically undeployable: one node, no edges, no
// Destination, no operator fields — the canvas's legitimate work-in-progress
const halfBuilt = {
  nodes: [{ id: 'positions-source', type: 'source', sourceId: 'positions' }],
  edges: [],
};

const dataflowId = await step('create Draft with a half-built graph (structural save accepts)', async () => {
  const res = await call('POST', `${api}/dataflows`, {
    body: { name: 'Positions Feed', config: halfBuilt },
  });
  expectStatus(res, 201);
  assert(res.body.slug === 'positions-feed', `slug is ${res.body.slug}, expected positions-feed`);
  assert(
    res.location?.endsWith(`/api/dataflows/${res.body.id}`),
    `Location is ${res.location}, expected …/api/dataflows/${res.body.id}`,
  );
  return res.body.id;
});

await step('the half-built Draft reads back unchanged', async () => {
  const res = await call('GET', `${api}/dataflows/${dataflowId}`);
  expectStatus(res, 200);
  assert(
    canonicalDroppingNulls(res.body.config) === canonicalDroppingNulls(halfBuilt),
    'the saved config does not round-trip',
  );
});

await step('save rejects a structurally broken graph (422 with violations)', async () => {
  const broken = { ...halfBuilt, edges: [{ from: 'positions-source', to: 'ghost' }] };
  const res = await call('PUT', `${api}/dataflows/${dataflowId}`, {
    body: { name: 'Positions Feed', config: broken },
  });
  expectStatus(res, 422);
  assert(res.body.title === 'Structural validation failed', `title is "${res.body.title}"`);
  assert(
    Array.isArray(res.body.violations) && res.body.violations.every((v) => v.message),
    'expected a violations array of {message}',
  );
});

await step('deploy of the half-built Draft is rejected (422 with semantic violations)', async () => {
  const res = await call('POST', `${api}/dataflows/${dataflowId}/deploy`);
  expectStatus(res, 422);
  assert(res.body.title === 'Semantic validation failed', `title is "${res.body.title}"`);
  const rules = (res.body.violations ?? []).map((v) => v.rule);
  for (const rule of ['delivery-count', 'missing-operator-fields']) {
    assert(rules.includes(rule), `expected rule "${rule}" among violations: ${rules.join(', ')}`);
  }
});

await step('the canonical Positions Feed saves over the Draft', async () => {
  const res = await call('PUT', `${api}/dataflows/${dataflowId}`, {
    body: { name: 'Positions Feed', config: canonicalConfig },
  });
  expectStatus(res, 200);
  assert(
    canonical(res.body.config) === canonical(canonicalConfig),
    'the canonical config does not round-trip',
  );
});

await step('deploy creates Deployment version 1', async () => {
  const res = await call('POST', `${api}/dataflows/${dataflowId}/deploy`);
  expectStatus(res, 201);
  assert(res.body.version === 1, `version is ${res.body.version}, expected 1`);
  assert(res.body.active === true, 'expected the new Deployment to be active');
  assert(
    res.location?.endsWith(`/api/dataflows/${dataflowId}/deployments/1`),
    `Location is ${res.location}`,
  );
});

await step('the flow is live in Kestra under the slug, with the version label', async () => {
  const res = await call('GET', kestraFlow, { auth: kestraAuth() });
  expectStatus(res, 200);
  assert(res.body.id === 'positions-feed', `flow id is ${res.body.id}`);
  assert(res.body.namespace === 'dataflow', `flow namespace is ${res.body.namespace}`);
  // Kestra has served labels both as a map and as [{key, value}]; accept either
  const labels = res.body.labels ?? {};
  const version = Array.isArray(labels)
    ? labels.find((l) => l.key === 'dataflow.version')?.value
    : labels['dataflow.version'];
  assert(version === '1', `label dataflow.version is ${version}, expected "1"`);
});

await step('deployment history exposes the frozen Execution Plan (matches the golden)', async () => {
  const history = await call('GET', `${api}/dataflows/${dataflowId}/deployments`);
  expectStatus(history, 200);
  assert(history.body.length === 1 && history.body[0].version === 1, 'expected exactly version 1');
  const frozen = await call('GET', `${api}/dataflows/${dataflowId}/deployments/1`);
  expectStatus(frozen, 200);
  assert(
    canonical(frozen.body.config) === canonical(canonicalConfig),
    'the frozen config differs from what was deployed',
  );
  assert(
    canonical(frozen.body.plan) === canonical(goldenPlan),
    'the frozen Execution Plan differs from e2e/golden/positions-feed.plan.json',
  );
});

const runId = await step('run-now (with a Business Date override) answers synchronously with a Run (202)', async () => {
  const res = await call('POST', `${api}/dataflows/${dataflowId}/run-now`, {
    body: { businessDate },
  });
  expectStatus(res, 202);
  assert(res.body.id, 'expected a run id');
  // eagerly QUEUED; the 5s poller may already have advanced it
  assert(
    ['QUEUED', 'RUNNING', 'FAILED'].includes(res.body.status),
    `run-now status is ${res.body.status}`,
  );
  assert(
    res.location?.endsWith(`/api/dataflows/${dataflowId}/runs/${res.body.id}`),
    `Location is ${res.location}`,
  );
  return res.body.id;
});

await step('the Run reaches terminal SUCCEEDED with timing and execution id', async () => {
  const deadline = Date.now() + 240_000;
  let run;
  for (;;) {
    const res = await call('GET', `${api}/dataflows/${dataflowId}/runs/${runId}`);
    expectStatus(res, 200);
    run = res.body;
    if (['SUCCEEDED', 'FAILED'].includes(run.status)) break;
    assert(Date.now() < deadline, `run still ${run.status} after 240s`);
    await sleep(3000);
  }
  // M1 asserted an honest FAILED here (the placeholder engine staged nothing, so
  // the staging pull had to fail). M2.5 replaced the placeholder with the real
  // hop × batch runner — the same lifecycle walk now proves the green path:
  // engine run, staged files, hidden upload, atomic rename, all succeeded.
  assert(run.status === 'SUCCEEDED', `run ended ${run.status}, but M2's engine must deliver`);
  assert(run.kestraExecutionId, 'expected a Kestra execution id');
  assert(run.detail, 'expected the raw Kestra state in detail');
  assert(run.startedAt && run.endedAt, `expected timing, got ${run.startedAt}..${run.endedAt}`);
  assert(
    new Date(run.endedAt) >= new Date(run.startedAt),
    `endedAt ${run.endedAt} precedes startedAt ${run.startedAt}`,
  );
});

await step('run history shows the Run with the delivered files and record counts', async () => {
  const res = await call('GET', `${api}/dataflows/${dataflowId}/runs`);
  expectStatus(res, 200);
  const run = res.body.find((r) => r.id === runId && r.status === 'SUCCEEDED');
  assert(run, 'the SUCCEEDED run is missing from history');
  // M2.7: the record answers "what did we ship?" — the five golden names with
  // per-file data-row counts, sorted by name, no SFTP access needed.
  assert(
    canonical(run.deliveredFiles) === canonical(goldenDeliveredFiles),
    `deliveredFiles ${JSON.stringify(run.deliveredFiles)} differ from the golden ${JSON.stringify(goldenDeliveredFiles)}`,
  );
});

await step('delete while deployed is rejected (409)', async () => {
  const res = await call('DELETE', `${api}/dataflows/${dataflowId}`);
  expectStatus(res, 409);
});

await step('undeploy removes the flow from Kestra', async () => {
  const res = await call('POST', `${api}/dataflows/${dataflowId}/undeploy`);
  expectStatus(res, 204);
  const gone = await call('GET', kestraFlow, { auth: kestraAuth() });
  expectStatus(gone, 404);
});

await step('the frozen snapshot outlives undeploy', async () => {
  const res = await call('GET', `${api}/dataflows/${dataflowId}/deployments/1`);
  expectStatus(res, 200);
  assert(res.body.active === false, 'version 1 should no longer be active');
});

await step('delete removes the undeployed Dataflow', async () => {
  const res = await call('DELETE', `${api}/dataflows/${dataflowId}`);
  expectStatus(res, 204);
  const gone = await call('GET', `${api}/dataflows/${dataflowId}`);
  expectStatus(gone, 404);
});

console.log('walkthrough complete');
