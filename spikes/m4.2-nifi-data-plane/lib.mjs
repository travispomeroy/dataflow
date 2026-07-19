// M4.2 spike helper — NOT production code. Findings live on issue #39.
// Minimal NiFi 2.10 REST client for the data-plane spike scripts.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'; // self-signed compose cert, spike only

const SPIKE_DIR = dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = join(SPIKE_DIR, '..', '..');
export const NIFI_API = 'https://localhost:8443/nifi-api';

export function env() {
  const out = {};
  for (const line of readFileSync(join(REPO_ROOT, 'infra', '.env'), 'utf8').split('\n')) {
    const m = line.match(/^([A-Z0-9_]+)=(.*)$/);
    if (m) out[m[1]] = m[2];
  }
  return out;
}

let token;
export async function fetchToken() {
  const { NIFI_USER, NIFI_PASSWORD } = env();
  const res = await fetch(`${NIFI_API}/access/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: NIFI_USER, password: NIFI_PASSWORD }),
  });
  if (res.status !== 201) throw new Error(`token: HTTP ${res.status}`);
  token = await res.text();
  return token;
}

export async function nifi(method, path, body) {
  if (!token) await fetchToken();
  const res = await fetch(`${NIFI_API}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path}: HTTP ${res.status}\n${text}`);
  try { return JSON.parse(text); } catch { return text; }
}

export async function rootPgId() {
  return (await nifi('GET', '/flow/process-groups/root')).processGroupFlow.id;
}

// Revision helpers: NiFi mutations need the current revision of the component.
export async function processorRevision(id) {
  return (await nifi('GET', `/processors/${id}`)).revision;
}
export async function pgRevision(id) {
  return (await nifi('GET', `/process-groups/${id}`)).revision;
}

export const REV0 = { version: 0 }; // creation revision

export async function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }
