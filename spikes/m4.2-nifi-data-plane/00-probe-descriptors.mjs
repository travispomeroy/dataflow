// Probe: instantiate every processor/service type the spike needs in a scratch PG
// and dump its property descriptor names — NiFi 2.x renamed properties, so we
// introspect the live instance instead of trusting docs. Scratch PG is deleted.
import { nifi, rootPgId, REV0 } from './lib.mjs';

const PROCESSORS = [
  'org.apache.nifi.processors.standard.GenerateFlowFile',
  'org.apache.nifi.processors.standard.InvokeHTTP',
  'org.apache.nifi.processors.standard.EvaluateJsonPath',
  'org.apache.nifi.processors.standard.RouteOnAttribute',
  'org.apache.nifi.processors.attributes.UpdateAttribute',
  'org.apache.nifi.processors.standard.MergeRecord',
  'org.apache.nifi.processors.standard.MergeContent',
  'org.apache.nifi.processors.standard.QueryRecord',
  'org.apache.nifi.processors.standard.JoinEnrichment',
  'org.apache.nifi.processors.aws.s3.PutS3Object',
];
const SERVICES = [
  'org.apache.nifi.json.JsonTreeReader',
  'org.apache.nifi.json.JsonRecordSetWriter',
  'org.apache.nifi.csv.CSVRecordSetWriter',
  'org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService',
];

const root = await rootPgId();
const pg = await nifi('POST', `/process-groups/${root}/process-groups`, {
  revision: REV0, component: { name: 'm42-descriptor-probe', position: { x: 0, y: 0 } },
});
const pgId = pg.id;

const report = {};
for (const type of PROCESSORS) {
  try {
    const p = await nifi('POST', `/process-groups/${pgId}/processors`, {
      revision: REV0, component: { type, position: { x: 0, y: 0 } },
    });
    const desc = p.component.config.descriptors;
    report[type] = {
      properties: Object.fromEntries(Object.entries(desc).map(([k, d]) => [k, {
        required: d.required, default: d.defaultValue ?? null,
        allowable: d.allowableValues?.map((a) => a.allowableValue.value) ?? null,
        controllerService: d.identifiesControllerService ?? null,
      }])),
      relationships: p.component.relationships.map((r) => r.name),
      supportsDynamic: p.component.supportsDynamicProperties ?? null,
    };
  } catch (e) { report[type] = { error: String(e).slice(0, 500) }; }
}
for (const type of SERVICES) {
  try {
    const s = await nifi('POST', `/process-groups/${pgId}/controller-services`, {
      revision: REV0, component: { type },
    });
    report[type] = {
      properties: Object.fromEntries(Object.entries(s.component.descriptors).map(([k, d]) => [k, {
        required: d.required, default: d.defaultValue ?? null,
        allowable: d.allowableValues?.map((a) => a.allowableValue.value) ?? null,
      }])),
    };
  } catch (e) { report[type] = { error: String(e).slice(0, 500) }; }
}

console.log(JSON.stringify(report, null, 2));

// cleanup: a never-started PG with never-enabled services deletes directly (M4.1 §3)
const rev = (await nifi('GET', `/process-groups/${pgId}`)).revision;
await nifi('DELETE', `/process-groups/${pgId}?version=${rev.version}&clientId=${rev.clientId ?? 'm42'}`);
console.error('probe PG deleted');
