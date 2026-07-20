// M4 walkthrough — the matrix stage of e2e/m4-gates.sh: the same Dataflow
// delivering the five golden CSVs byte-identically on both engines, flipped
// between them through the ordinary Draft-update → redeploy lifecycle.
//
// Deploy the canonical Positions Feed with `engine: nifi` → run-now with the
// pinned Business Date → poll SUCCEEDED → fetch the five CSVs off Pomeroy
// Provider's SFTP and byte-compare against the existing e2e/golden/delivered/
// files M2 froze (identical bytes from a second engine *is* the milestone) →
// flip the same Dataflow to `engine: hop`, redeploy → assert the process group
// and parameter context are gone from NiFi (supersession teardown, observed
// through NiFi's own REST API) → rerun → byte-compare again → undeploy and
// delete. End posture: the Dataflow gone, no process group, no Schedule firing.
//
// Sequential and fail-fast like the m1/m2 walkthroughs; asserts only external
// surfaces — the control plane's API, NiFi's REST API, and the delivered bytes
// on SFTP — never NiFi internals reached around the platform. Node built-ins
// only; NIFI_* and SFTP_PASSWORD come from infra/.env (node --env-file, as the
// m0 smoke and m1 walkthrough are run).

import { readFile, writeFile, readdir, mkdtemp, rm } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { request as httpsRequest } from 'node:https';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const api = 'http://localhost:8085/api';
const nifiBase = 'https://localhost:8443';
const repoRoot = join(import.meta.dirname, '..');

// Pinned by spec #19: inside the fixtures' trade-date window, so the delivered
// names and bytes — and the run record derived from them — are fully golden.
const businessDate = '2026-07-17';

const env = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`missing env var ${name} — run via e2e/m4-gates.sh`);
  return value;
};

const readJson = async (...parts) =>
  JSON.parse(await readFile(join(import.meta.dirname, ...parts), 'utf8'));

// Canonical form for deep equality (m1/m2 walkthrough prior art): objects with
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

// -- NiFi REST (M0 smoke posture) ---------------------------------------------
// NiFi serves HTTPS with a certificate it generates at first boot; Node's built-in
// fetch has no per-request escape hatch for the self-signed cert, so these calls
// go through node:https with rejectUnauthorized:false — the TLS path is still
// exercised, everything else is strict. Token: POST form-urlencoded, bare JWT back.
const nifiRequest = (path, { method = 'GET', headers = {}, body } = {}) =>
  new Promise((resolve, reject) => {
    const req = httpsRequest(
      `${nifiBase}${path}`,
      { method, headers, rejectUnauthorized: false },
      (res) => {
        let text = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => { text += chunk; });
        res.on('end', () => resolve({ status: res.statusCode, text }));
      },
    );
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });

const nifiToken = async () => {
  const res = await nifiRequest('/nifi-api/access/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      username: env('NIFI_USER'),
      password: env('NIFI_PASSWORD'),
    }).toString(),
  });
  assert(res.status === 201 && res.text, `NiFi token endpoint: expected 201 with a token, got ${res.status}`);
  return res.text;
};

const nifiJson = async (token, path) => {
  const res = await nifiRequest(path, { headers: { authorization: `Bearer ${token}` } });
  assert(res.status === 200, `NiFi GET ${path}: expected 200, got ${res.status}`);
  return JSON.parse(res.text);
};

// -- SFTP fetch + byte-compare ------------------------------------------------
// The same non-interactive sftp posture as the m0 probe and the m2/m3 gates:
// committed host key, askpass for the password, no known_hosts outside the repo.
// The two engine deliveries write the same golden filenames, so the byte-compare
// must happen inside the walkthrough between the two runs — the gate shell would
// only ever see the last delivery. -b aborts on the first failing get, so a
// missing file fails before any compare runs.
const goldenDir = join(import.meta.dirname, 'golden', 'delivered');

const execFileAsync = (file, args, options) =>
  new Promise((resolve, reject) => {
    execFile(file, args, options, (error, stdout, stderr) => {
      if (error) reject(new Error(`${error.message}\n${stderr}`));
      else resolve({ stdout, stderr });
    });
  });

const fetchDeliveredAndCompare = async (goldenNames) => {
  const fetchDir = await mkdtemp(join(tmpdir(), 'm4-delivered-'));
  try {
    const batch =
      'cd upload/pomeroy\n' +
      goldenNames.map((name) => `get ${name} ${fetchDir}/\n`).join('');
    const batchFile = join(fetchDir, 'batch.sftp');
    await writeFile(batchFile, batch, 'utf8');
    await execFileAsync(
      'sftp',
      [
        '-P', '2222',
        '-o', 'BatchMode=no',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', `UserKnownHostsFile=${join(repoRoot, 'infra', 'sftp', 'known_hosts')}`,
        '-o', 'GlobalKnownHostsFile=/dev/null',
        '-o', 'PreferredAuthentications=password',
        '-b', batchFile,
        'pomeroy@localhost',
      ],
      {
        env: {
          ...process.env,
          SFTP_PASSWORD: env('SFTP_PASSWORD'),
          SSH_ASKPASS: join(repoRoot, 'infra', 'sftp', 'askpass.sh'),
          SSH_ASKPASS_REQUIRE: 'force',
        },
      },
    );
    for (const name of goldenNames) {
      const [delivered, golden] = await Promise.all([
        readFile(join(fetchDir, name)),
        readFile(join(goldenDir, name)),
      ]);
      assert(
        delivered.equals(golden),
        `delivered ${name} (${delivered.length} bytes) differs from the oracle golden (${golden.length} bytes)`,
      );
    }
  } finally {
    await rm(fetchDir, { recursive: true, force: true });
  }
};

// What the Run record must say was shipped: name + data-row count per golden CSV,
// sorted by name (the committed goldens are the oracle's output, so this assertion
// shares their independent provenance). Lines minus header; the CSV contract
// guarantees a trailing newline, hence the extra -1. (m2-walkthrough prior art.)
const goldenNames = (await readdir(goldenDir)).sort();
assert(goldenNames.length === 5, `expected five golden CSVs, found ${goldenNames.length}`);
const goldenDeliveredFiles = await Promise.all(
  goldenNames.map(async (name) => ({
    name,
    records: (await readFile(join(goldenDir, name), 'utf8')).split('\n').length - 2,
  })),
);

// The canonical config is `engine: hop`; the two variants flip only the operator
// axes (ADR-0003) — the logical DAG is untouched, which is the pluggability claim.
const canonicalConfig = await readJson('canonical', 'positions-feed.config.json');
const nifiConfig = { ...canonicalConfig, engine: 'nifi', executionModel: 'server' };
const hopConfig = { ...canonicalConfig, engine: 'hop', executionModel: 'batch' };

// Deploy → run-now → SUCCEEDED → run-history record → fetch + byte-compare, the
// deliver-and-verify half shared by both engine passes. Returns nothing; throws
// (via step's process.exit) on any divergence.
const deployRunAndVerify = async (dataflowId, engine, expectVersion) => {
  await step(`deploy creates Deployment version ${expectVersion} (engine ${engine})`, async () => {
    const res = await call('POST', `${api}/dataflows/${dataflowId}/deploy`);
    expectStatus(res, 201);
    assert(res.body.version === expectVersion, `version is ${res.body.version}, expected ${expectVersion}`);
  });

  const runId = await step(`run-now businessDate=${businessDate} answers with a Run (202)`, async () => {
    const res = await call('POST', `${api}/dataflows/${dataflowId}/run-now`, { body: { businessDate } });
    expectStatus(res, 202);
    assert(res.body.id, 'expected a run id');
    return res.body.id;
  });

  await step(`the ${engine} Run reaches terminal SUCCEEDED (240s budget, M1 notes)`, async () => {
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
    assert(run.status === 'SUCCEEDED', `run ended ${run.status} — the ${engine} engine must deliver`);
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

  await step(`the five files fetched off SFTP byte-match the oracle goldens (${engine} delivery)`, async () => {
    await fetchDeliveredAndCompare(goldenNames);
  });

  return runId;
};

// -- The matrix ---------------------------------------------------------------

const dataflowId = await step('the canonical Positions Feed saves as a new Draft with engine=nifi', async () => {
  const res = await call('POST', `${api}/dataflows`, {
    body: { name: 'Positions Feed', config: nifiConfig },
  });
  expectStatus(res, 201);
  assert(res.body.slug === 'positions-feed', `slug is ${res.body.slug}, expected positions-feed`);
  assert(res.body.config.engine === 'nifi', `saved engine is ${res.body.config.engine}, expected nifi`);
  return res.body.id;
});

const nifiRunId = await deployRunAndVerify(dataflowId, 'nifi', 1);

await step('flip the Draft to engine=hop (an ordinary Draft update, no special mechanism)', async () => {
  const res = await call('PUT', `${api}/dataflows/${dataflowId}`, {
    body: { name: 'Positions Feed', config: hopConfig },
  });
  expectStatus(res, 200);
  assert(res.body.config.engine === 'hop', `saved engine is ${res.body.config.engine}, expected hop`);
});

const hopRunId = await deployRunAndVerify(dataflowId, 'hop', 2);

// Supersession teardown, observed through NiFi's own API: the hop redeploy over
// the nifi Deployment must have deleted the orphaned process group AND parameter
// context (deleting the group does not cascade to the context — RestNiFiDeployments
// deletes it in its own right). Both are named by the slug.
await step('the superseded process group and parameter context are gone from NiFi', async () => {
  const token = await nifiToken();
  const groups = await nifiJson(token, '/nifi-api/flow/process-groups/root');
  const strayGroup = groups.processGroupFlow.flow.processGroups.find(
    (g) => g.component.name === 'positions-feed',
  );
  assert(!strayGroup, 'a process group named positions-feed still exists — supersession did not tear it down');
  const contexts = await nifiJson(token, '/nifi-api/flow/parameter-contexts');
  const strayContext = contexts.parameterContexts.find((c) => c.component.name === 'positions-feed');
  assert(!strayContext, 'a parameter context named positions-feed still exists — supersession did not tear it down');
});

await step('undeploy (world hygiene: no live Schedule while the world is left running)', async () => {
  const res = await call('POST', `${api}/dataflows/${dataflowId}/undeploy`);
  expectStatus(res, 204);
});

await step('delete leaves the stage clean (no Dataflow, no Schedule, no process group)', async () => {
  const res = await call('DELETE', `${api}/dataflows/${dataflowId}`);
  expectStatus(res, 204);
});

console.log(`walkthrough complete (nifi run ${nifiRunId}, hop run ${hopRunId} — same bytes, both engines)`);
