// Teardown: M4.1 §3 ordering — stop PG → drop queues → disable services →
// delete PG — then remove the spike's staged MinIO objects. Leaves the world
// as found. Also used mid-spike for clean rebuilds.
import { execSync } from 'node:child_process';
import { join } from 'node:path';
import { nifi, sleep, REPO_ROOT, env } from './lib.mjs';

const root = (await nifi('GET', '/flow/process-groups/root')).processGroupFlow;
const pgEntity = root.flow.processGroups.find((g) => g.component.name === 'm42-data-plane');

if (!pgEntity) {
  console.log('no m42-data-plane process group — nothing to delete in NiFi');
} else {
  const pgId = pgEntity.id;
  await nifi('PUT', `/flow/process-groups/${pgId}`, { id: pgId, state: 'STOPPED' });
  const drop = await nifi('POST', `/process-groups/${pgId}/empty-all-connections-requests`);
  for (let i = 0; i < 30; i++) {
    const st = await nifi('GET', `/process-groups/${pgId}/empty-all-connections-requests/${drop.dropRequest.id}`);
    if (st.dropRequest.finished) break;
    await sleep(500);
  }
  await nifi('DELETE', `/process-groups/${pgId}/empty-all-connections-requests/${drop.dropRequest.id}`);
  await nifi('PUT', `/flow/process-groups/${pgId}/controller-services`, { id: pgId, state: 'DISABLED' });
  await sleep(3000); // services settle in ~2s (M4.1)
  const rev = (await nifi('GET', `/process-groups/${pgId}`)).revision;
  await nifi('DELETE', `/process-groups/${pgId}?version=${rev.version}&clientId=${rev.clientId ?? 'm42'}`);
  console.log('process group deleted');
}

const { MINIO_ROOT_USER, MINIO_ROOT_PASSWORD } = env();
try {
  execSync(
    `docker exec dataflow-minio-1 sh -c "mc alias set local http://localhost:9000 ${MINIO_ROOT_USER} ${MINIO_ROOT_PASSWORD} >/dev/null && mc rm -r --force local/staging/m42-positions-feed/ 2>/dev/null; true"`,
    { stdio: 'inherit', cwd: REPO_ROOT },
  );
  console.log('staged spike objects removed');
} catch { console.log('minio cleanup skipped (container not running?)'); }
