// M2 walkthrough — the deploy-and-deliver stage of e2e/m2-gates.sh.
// The M1 walkthrough already proved the whole lifecycle (including a run to
// SUCCEEDED and the delivered-files history); this walk exists to leave a
// fresh delivery on the SFTP server for the gate's byte-compare, and to pin
// the run-history record against the committed goldens one more time on the
// run whose files are actually fetched. Sequential and fail-fast like the M1
// walkthrough; asserts only external surfaces (API responses). Node built-ins
// only. Prints the run id on success for the gate's log.

import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const api = 'http://localhost:8085/api';

const readJson = async (...parts) =>
  JSON.parse(await readFile(join(import.meta.dirname, ...parts), 'utf8'));

// Canonical form for deep equality (m1-walkthrough prior art): objects with
// sorted keys, then stringify — nulls stay significant.
const canonical = (value) =>
  JSON.stringify(value, (_key, v) =>
    v !== null && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
      : v,
  );

const assert = (cond, message) => {
  if (!cond) throw new Error(message);
};

const call = async (method, url, { body } = {}) => {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'content-type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  return { status: res.status, body: parsed };
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

// Pinned by spec #19: inside the fixtures' trade-date window, so the delivered
// names and bytes — and the run record derived from them — are fully golden.
const businessDate = '2026-07-17';

// What the Run record must say was shipped: name + data-row count per golden
// CSV, sorted by name (the committed goldens are the oracle's output, so this
// assertion shares their independent provenance). Lines minus header; the CSV
// contract guarantees a trailing newline, hence the extra -1.
const goldenDeliveredFiles = await (async () => {
  const dir = join(import.meta.dirname, 'golden', 'delivered');
  const names = (await readdir(dir)).sort();
  assert(names.length === 5, `expected five golden CSVs, found ${names.length}`);
  return Promise.all(
    names.map(async (name) => ({
      name,
      records: (await readFile(join(dir, name), 'utf8')).split('\n').length - 2,
    })),
  );
})();

const dataflowId = await step('the canonical Positions Feed saves as a new Draft', async () => {
  const res = await call('POST', `${api}/dataflows`, {
    body: { name: 'Positions Feed', config: canonicalConfig },
  });
  expectStatus(res, 201);
  assert(res.body.slug === 'positions-feed', `slug is ${res.body.slug}, expected positions-feed`);
  return res.body.id;
});

await step('deploy creates Deployment version 1', async () => {
  const res = await call('POST', `${api}/dataflows/${dataflowId}/deploy`);
  expectStatus(res, 201);
  assert(res.body.version === 1, `version is ${res.body.version}, expected 1`);
});

const runId = await step(`run-now with businessDate=${businessDate} answers with a Run (202)`, async () => {
  const res = await call('POST', `${api}/dataflows/${dataflowId}/run-now`, {
    body: { businessDate },
  });
  expectStatus(res, 202);
  assert(res.body.id, 'expected a run id');
  return res.body.id;
});

await step('the Run reaches terminal SUCCEEDED (240s budget, M1 notes)', async () => {
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
  assert(run.status === 'SUCCEEDED', `run ended ${run.status} — the engine must deliver`);
});

await step('run history returns the run with exactly the golden file names and record counts', async () => {
  const res = await call('GET', `${api}/dataflows/${dataflowId}/runs`);
  expectStatus(res, 200);
  const run = res.body.find((r) => r.id === runId && r.status === 'SUCCEEDED');
  assert(run, 'the SUCCEEDED run is missing from history');
  assert(
    canonical(run.deliveredFiles) === canonical(goldenDeliveredFiles),
    `deliveredFiles ${JSON.stringify(run.deliveredFiles)} differ from the golden ${JSON.stringify(goldenDeliveredFiles)}`,
  );
});

await step('undeploy (world hygiene: no live Schedule while the world is left running)', async () => {
  // Not an M2 assertion — M1 owns the undeploy contract. Without this the
  // daily Schedule would keep firing runs in the left-running world. The
  // Dataflow, its frozen snapshot, and the run history stay in the database;
  // the delivered files stay on the SFTP server for the gate's byte-compare.
  const res = await call('POST', `${api}/dataflows/${dataflowId}/undeploy`);
  expectStatus(res, 204);
});

console.log(`walkthrough complete (run ${runId} delivered)`);
