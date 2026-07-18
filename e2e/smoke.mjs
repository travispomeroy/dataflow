// M0 smoke checks — the behavior stage of e2e/m0-gates.sh.
// Node built-ins only (fetch); credentials come from infra/.env, injected by
// the gate script via node --env-file. Later M0 tickets append check() calls.

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
