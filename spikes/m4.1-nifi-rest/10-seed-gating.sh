#!/usr/bin/env bash
# Bonus (feeds M4.3/M4.4) — the run protocol needs "start the PG with the seed
# left stopped". Does PG-start skip a DISABLED processor? Can a DISABLED seed be
# enabled and RUN_ONCE'd afterwards? Builds its own tiny PG, cleans up after.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ROOT=$(root_pg_id)

PG=$(nifi POST "/process-groups/$ROOT/process-groups" -d '{
  "revision": {"version": 0},
  "component": {"name": "m41-seed-gating", "position": {"x": 0, "y": 600}}
}' | jq -r '.id')

GEN=$(nifi POST "/process-groups/$PG/processors" -d '{
  "revision": {"version": 0},
  "component": {
    "type": "org.apache.nifi.processors.standard.GenerateFlowFile",
    "position": {"x": 0, "y": 0}, "name": "seed",
    "config": {"schedulingPeriod": "1 min"}
  }
}' | jq -r '.id')

SINK=$(nifi POST "/process-groups/$PG/processors" -d '{
  "revision": {"version": 0},
  "component": {
    "type": "org.apache.nifi.processors.attributes.UpdateAttribute",
    "position": {"x": 0, "y": 300}, "name": "sink",
    "config": {"autoTerminatedRelationships": ["success"]}
  }
}' | jq -r '.id')

CONN=$(nifi POST "/process-groups/$PG/connections" -d "{
  \"revision\": {\"version\": 0},
  \"component\": {
    \"source\": {\"id\": \"$GEN\", \"groupId\": \"$PG\", \"type\": \"PROCESSOR\"},
    \"destination\": {\"id\": \"$SINK\", \"groupId\": \"$PG\", \"type\": \"PROCESSOR\"},
    \"selectedRelationships\": [\"success\"]
  }
}" | jq -r '.id')

echo "--- disable the seed, then start the PG ---"
REV=$(nifi GET "/processors/$GEN" | jq -r '.revision.version')
nifi PUT "/processors/$GEN/run-status" -d "{\"revision\":{\"version\":$REV},\"state\":\"DISABLED\"}" >/dev/null
nifi PUT "/flow/process-groups/$PG" -d "{\"id\":\"$PG\",\"state\":\"RUNNING\"}" >/dev/null
sleep 3
echo "seed: $(nifi GET "/processors/$GEN" | jq -r '.component.state'), sink: $(nifi GET "/processors/$SINK" | jq -r '.component.state')"
echo "queued after 3s of PG running: $(nifi GET "/flow/connections/$CONN/status" | jq -r '.connectionStatus.aggregateSnapshot.flowFilesQueued') (0 = PG start skipped the DISABLED seed)"

echo "--- RUN_ONCE directly on the DISABLED seed (expect rejection) ---"
REV=$(nifi GET "/processors/$GEN" | jq -r '.revision.version')
curl -sk -o /dev/stdout -w '\n[HTTP %{http_code}]\n' -X PUT "$NIFI_API/processors/$GEN/run-status" \
  -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' \
  -d "{\"revision\":{\"version\":$REV},\"state\":\"RUN_ONCE\"}"

echo "--- enable (DISABLED -> STOPPED), then RUN_ONCE ---"
REV=$(nifi GET "/processors/$GEN" | jq -r '.revision.version')
nifi PUT "/processors/$GEN/run-status" -d "{\"revision\":{\"version\":$REV},\"state\":\"STOPPED\"}" >/dev/null
sleep 1
REV=$(nifi GET "/processors/$GEN" | jq -r '.revision.version')
nifi PUT "/processors/$GEN/run-status" -d "{\"revision\":{\"version\":$REV},\"state\":\"RUN_ONCE\"}" >/dev/null
sleep 2
echo "seed: $(nifi GET "/processors/$GEN" | jq -r '.component.state'); sink consumed it if queue is 0 and PG saw traffic:"
nifi GET "/flow/process-groups/$PG/status" | jq '.processGroupStatus.aggregateSnapshot | {flowFilesQueued, flowFilesOut: .flowFilesSent, flowFilesTransferred}'

echo "--- cleanup ---"
nifi PUT "/flow/process-groups/$PG" -d "{\"id\":\"$PG\",\"state\":\"STOPPED\"}" >/dev/null
sleep 1
REV=$(nifi GET "/process-groups/$PG" | jq -r '.revision.version')
curl -sk -o /dev/null -w 'DELETE pg: HTTP %{http_code}\n' -X DELETE \
  "$NIFI_API/process-groups/$PG?version=$REV" -H "Authorization: Bearer $(token)"
