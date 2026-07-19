// One spike run: usage `node 02-run.mjs <runTag>` — stages objects under
// staging/m42-positions-feed/<runTag>/. Follows the M4.1 run protocol:
// drop queues → set run tag → start PG (seeds DISABLED, skipped) → enable
// seeds → RUN_ONCE each → poll drain (0 queued × 3 consecutive) → check
// failure funnel → stop PG → re-disable seeds.
import { nifi, sleep } from './lib.mjs';

const runTag = process.argv[2];
if (!/^[a-z0-9-]+$/.test(runTag ?? '')) throw new Error('usage: node 02-run.mjs <runTag>');

const root = (await nifi('GET', '/flow/process-groups/root')).processGroupFlow;
const pgEntity = root.flow.processGroups.find((g) => g.component.name === 'm42-data-plane');
if (!pgEntity) throw new Error('m42-data-plane not found — run 01-build.mjs');
const pgId = pgEntity.id;

const procs = (await nifi('GET', `/process-groups/${pgId}/processors`)).processors;
const byName = (n) => procs.find((p) => p.component.name === n) ?? (() => { throw new Error(`no processor ${n}`); })();
const seeds = ['positions: seed page 1', 'investors: seed page 1', 'orders: seed page 1'].map(byName);
const stage = byName('stage to MinIO');

async function runStatus(id, state) {
  const rev = (await nifi('GET', `/processors/${id}`)).revision;
  await nifi('PUT', `/processors/${id}/run-status`, { revision: rev, state });
}

// 1. ensure stopped, seeds DISABLED (an enabled seed would timer-fire on PG
// start — the race M4.1 documented; we hit it live mid-spike), then drop
// any queue residue (async request + poll + delete)
await nifi('PUT', `/flow/process-groups/${pgId}`, { id: pgId, state: 'STOPPED' });
for (const s of seeds) await runStatus(s.id, 'DISABLED');
const drop = await nifi('POST', `/process-groups/${pgId}/empty-all-connections-requests`);
const dropId = drop.dropRequest.id;
for (let i = 0; i < 30; i++) {
  const st = await nifi('GET', `/process-groups/${pgId}/empty-all-connections-requests/${dropId}`);
  if (st.dropRequest.finished) break;
  await sleep(500);
}
await nifi('DELETE', `/process-groups/${pgId}/empty-all-connections-requests/${dropId}`);
console.log('queues dropped');

// 2. point the staging write at this run's prefix
{
  const cur = await nifi('GET', `/processors/${stage.id}`);
  await nifi('PUT', `/processors/${stage.id}`, {
    revision: cur.revision,
    component: {
      id: stage.id,
      config: { properties: { 'Object Key': `m42-positions-feed/${runTag}/\${filename}` } },
    },
  });
  console.log(`run tag = ${runTag}`);
}

// 3. start PG (DISABLED seeds are skipped), then enable + RUN_ONCE each seed
await nifi('PUT', `/flow/process-groups/${pgId}`, { id: pgId, state: 'RUNNING' });
for (const s of seeds) {
  await runStatus(s.id, 'STOPPED');   // DISABLED → enabled-but-stopped
  await runStatus(s.id, 'RUN_ONCE');  // exactly one seed flowfile
  console.log(`RUN_ONCE ${s.component.name}`);
}

// 4. drain: PG-wide queued count 0 for 3 consecutive polls
let zeroes = 0, drained = false;
const t0 = Date.now();
for (let i = 0; i < 240; i++) {
  const st = await nifi('GET', `/flow/process-groups/${pgId}/status?recursive=true`);
  const queued = st.processGroupStatus.aggregateSnapshot.flowFilesQueued;
  zeroes = queued === 0 ? zeroes + 1 : 0;
  if (zeroes >= 3) { drained = true; break; }
  await sleep(1000);
}
console.log(drained ? `drained in ${((Date.now() - t0) / 1000).toFixed(1)}s` : 'DRAIN TIMEOUT');

// 5. failure check: any queue whose destination is the funnel
const conns = (await nifi('GET', `/process-groups/${pgId}/connections`)).connections;
let failures = 0;
for (const c of conns.filter((c) => c.destinationType === 'FUNNEL')) {
  const st = await nifi('GET', `/connections/${c.id}`);
  const queued = (await nifi('GET', `/flow/connections/${c.id}/status`)).connectionStatus.aggregateSnapshot.flowFilesQueued;
  if (queued > 0) {
    failures += queued;
    console.log(`FAILURE queue "${st.component.name}": ${queued} flowfile(s)`);
    // surface the first offender's attributes for diagnosis
    const listing = await nifi('POST', `/flowfile-queues/${c.id}/listing-requests`);
    await sleep(500);
    const done = await nifi('GET', `/flowfile-queues/${c.id}/listing-requests/${listing.listingRequest.id}`);
    for (const ff of done.listingRequest.flowFileSummaries.slice(0, 2)) {
      const detail = await nifi('GET', `/flowfile-queues/${c.id}/flowfiles/${ff.uuid}`);
      console.log('  attrs:', JSON.stringify(detail.flowFile.attributes));
    }
    await nifi('DELETE', `/flowfile-queues/${c.id}/listing-requests/${listing.listingRequest.id}`);
  }
}

// 6. stop; seeds back to DISABLED so the next PG start cannot timer-fire them
await nifi('PUT', `/flow/process-groups/${pgId}`, { id: pgId, state: 'STOPPED' });
for (const s of seeds) await runStatus(s.id, 'DISABLED');

if (!drained || failures > 0) { console.log('RUN FAILED'); process.exit(1); }
console.log('RUN OK');
