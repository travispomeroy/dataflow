// Export the live spike flow as a flow-definition snapshot (the same
// "Download flow definition" shape M4.1 proved uploads whole) — the committed
// reference artifact for compiler-nifi (M4.3). Sensitive values (MinIO secret)
// are omitted by NiFi on export.
import { writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { nifi } from './lib.mjs';

const root = (await nifi('GET', '/flow/process-groups/root')).processGroupFlow;
const pgEntity = root.flow.processGroups.find((g) => g.component.name === 'm42-data-plane');
if (!pgEntity) throw new Error('m42-data-plane not found');

const def = await nifi('GET', `/process-groups/${pgEntity.id}/download?includeReferencedServices=true`);
const out = join(dirname(fileURLToPath(import.meta.url)), 'artifacts', 'm42-flow-definition.json');
writeFileSync(out, JSON.stringify(def, null, 2) + '\n');
console.log(`exported ${out}`);
console.log(`processors: ${def.flowContents.processors.length}, connections: ${def.flowContents.connections.length}, services: ${(def.flowContents.controllerServices ?? []).length}`);
