// M0 smoke checks — the behavior stage of e2e/m0-gates.sh.
// Node built-ins only (fetch, node:https); credentials come from infra/.env, injected by
// the gate script via node --env-file. Later M0 tickets append check() calls.

import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { request as httpsRequest } from "node:https";

const env = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`missing env var ${name} — run via e2e/m0-gates.sh`);
  return value;
};

const checks = [];
const check = (name, fn) => checks.push({ name, fn });

// v1.x API paths carry the tenant; OSS single-tenant is always "main"
const kestraFlowSearch = 'http://localhost:8080/api/v1/main/flows/search?page=1&size=1';

check('Kestra API answers an authenticated request', async () => {
  const auth = Buffer.from(
    `${env('KESTRA_UI_USER')}:${env('KESTRA_UI_PASSWORD')}`,
  ).toString('base64');
  const res = await fetch(kestraFlowSearch, {
    headers: { authorization: `Basic ${auth}` },
  });
  if (!res.ok) throw new Error(`expected 200, got ${res.status}`);
  const body = await res.json();
  if (!Array.isArray(body.results)) throw new Error('flow search returned no results array');
});

// guards the configured credentials actually took effect: Kestra silently
// discards weak/invalid basic-auth config and falls back to its setup page
check('Kestra API rejects an unauthenticated request', async () => {
  const res = await fetch(kestraFlowSearch);
  if (res.status !== 401) throw new Error(`expected 401, got ${res.status}`);
});

check('MinIO liveness endpoint is OK', async () => {
  const res = await fetch('http://localhost:9000/minio/health/live');
  if (!res.ok) throw new Error(`expected 200, got ${res.status}`);
});

// -- Mock upstream APIs (M0.4): strict pagination + reconciliation ------------
// The committed canonical dataset is the expected value; the mocks are the
// system under test. Page sizes are the one fact not derivable from the data —
// the generator emits them in a manifest next to the stubs it derives them from.

const fixturesDir = join(import.meta.dirname, '..', 'infra', 'fixtures');
const readJson = async (...parts) => JSON.parse(await readFile(join(...parts), 'utf8'));

// Canonical form for deep equality: objects with sorted keys, then stringify
const canonical = (value) =>
  JSON.stringify(value, (_key, v) =>
    v !== null && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
      : v,
  );

const wiremock = 'http://localhost:8082';
const apis = ['investors', 'orders', 'positions'];
const walkedPageSizes = new Map();

// Expected values for one API: the committed dataset plus its page size from
// the generator's manifest (the one fact not derivable from the dataset)
const expectedPaging = async (api) => {
  const dataset = await readJson(fixturesDir, 'data', `${api}.json`);
  const { pageSize } = (await readJson(fixturesDir, 'wiremock', 'manifest.json'))[api];
  return { dataset, pageSize, totalPages: Math.ceil(dataset.length / pageSize) };
};

for (const api of apis) {
  check(`/${api} paginates strictly and the union is exactly the committed dataset`, async () => {
    const { dataset, pageSize, totalPages } = await expectedPaging(api);
    if (totalPages < 3) throw new Error(`expected >= 3 pages, dataset yields ${totalPages}`);

    const union = [];
    for (let page = 1; page <= totalPages; page++) {
      const res = await fetch(`${wiremock}/${api}?page=${page}&pageSize=${pageSize}`);
      if (!res.ok) throw new Error(`page ${page}: expected 200, got ${res.status}`);
      const body = await res.json();
      for (const [field, expected] of [
        ['page', page],
        ['pageSize', pageSize],
        ['totalPages', totalPages],
        ['totalItems', dataset.length],
      ]) {
        if (body[field] !== expected) {
          throw new Error(`page ${page}: envelope ${field} is ${body[field]}, expected ${expected}`);
        }
      }
      if (!Array.isArray(body.data)) throw new Error(`page ${page}: envelope data is not an array`);
      union.push(...body.data);
    }
    if (canonical(union) !== canonical(dataset)) {
      throw new Error(`union of all pages differs from committed data/${api}.json (${union.length} vs ${dataset.length} records)`);
    }
    walkedPageSizes.set(api, pageSize);
  });

  check(`/${api} rejects a wrong pageSize and a page past the last (404)`, async () => {
    const { pageSize, totalPages } = await expectedPaging(api);
    for (const query of [`page=1&pageSize=${pageSize + 1}`, `page=${totalPages + 1}&pageSize=${pageSize}`]) {
      const res = await fetch(`${wiremock}/${api}?${query}`);
      if (res.status !== 404) throw new Error(`?${query}: expected 404, got ${res.status}`);
    }
  });
}

// -- Engines (M0.6): NiFi token dance over TLS --------------------------------
// NiFi 2.x serves HTTPS with a certificate it generates at first boot, so the
// identity churns every `compose down --volumes`. The checks therefore accept
// any certificate (the TLS path is still exercised end-to-end) but everything
// else is strict: creds must mint a bearer token and the token must be honored.

const nifiBase = 'https://localhost:8443';

// Node's built-in fetch offers no per-request escape hatch for a self-signed
// cert, so these two calls go through node:https instead
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

const nifiToken = (username, password) =>
  nifiRequest('/nifi-api/access/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password }).toString(),
  });

check('NiFi mints a bearer token over TLS and honors it on an authenticated call', async () => {
  const token = await nifiToken(env('NIFI_USER'), env('NIFI_PASSWORD'));
  if (token.status !== 201 || !token.text) {
    throw new Error(`token endpoint: expected 201 with a token, got ${token.status}`);
  }
  const me = await nifiRequest('/nifi-api/flow/current-user', {
    headers: { authorization: `Bearer ${token.text}` },
  });
  if (me.status !== 200) throw new Error(`current-user: expected 200, got ${me.status}`);
  const { identity } = JSON.parse(me.text);
  if (identity !== env('NIFI_USER')) {
    throw new Error(`current-user identity is ${identity}, expected ${env('NIFI_USER')}`);
  }
});

// guards the configured credentials actually took effect: NiFi silently
// discards a <12-char single-user password and generates random credentials
check('NiFi rejects wrong credentials at the token endpoint', async () => {
  const res = await nifiToken(env('NIFI_USER'), `wrong-${env('NIFI_PASSWORD')}`);
  if (res.status < 400 || res.status >= 500) {
    throw new Error(`expected a 4xx rejection, got ${res.status}`);
  }
});

check('the three APIs use three different page sizes', async () => {
  if (walkedPageSizes.size !== apis.length) {
    throw new Error('page walks did not all pass; cannot compare page sizes');
  }
  const sizes = [...walkedPageSizes.values()];
  if (new Set(sizes).size !== sizes.length) {
    throw new Error(`page sizes are not pairwise distinct: ${sizes.join(', ')}`);
  }
});

let failed = 0;
for (const { name, fn } of checks) {
  try {
    await fn();
    console.log(`ok    ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`FAIL  ${name} — ${err.message}`);
  }
}
process.exit(failed === 0 ? 0 : 1);
