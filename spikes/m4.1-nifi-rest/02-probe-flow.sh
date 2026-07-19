#!/usr/bin/env bash
# Build the probe process group imperatively: GenerateFlowFile -> UpdateAttribute
# (left stopped, so flowfiles queue in the connection), plus an enabled CSVReader
# controller service (a deliberate delete-blocker for Q3).
# Ids land in $TMPDIR/m4.1-probe.json for the later scripts.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
STATE="${TMPDIR:-/tmp}/m4.1-probe.json"

ROOT=$(root_pg_id)
echo "root pg: $ROOT"

PG=$(nifi POST "/process-groups/$ROOT/process-groups" -d '{
  "revision": {"version": 0},
  "component": {"name": "m41-probe", "position": {"x": 0, "y": 0}}
}' | jq -r '.id')
echo "probe pg: $PG"

GEN=$(nifi POST "/process-groups/$PG/processors" -d '{
  "revision": {"version": 0},
  "component": {
    "type": "org.apache.nifi.processors.standard.GenerateFlowFile",
    "position": {"x": 0, "y": 0},
    "name": "seed",
    "config": {"schedulingPeriod": "1 min", "properties": {"generate-ff-custom-text": "probe"}}
  }
}' | jq -r '.id')
echo "GenerateFlowFile: $GEN"

UPD=$(nifi POST "/process-groups/$PG/processors" -d '{
  "revision": {"version": 0},
  "component": {
    "type": "org.apache.nifi.processors.attributes.UpdateAttribute",
    "position": {"x": 0, "y": 300},
    "name": "sink",
    "config": {"autoTerminatedRelationships": ["success"]}
  }
}' | jq -r '.id')
echo "UpdateAttribute: $UPD"

CONN=$(nifi POST "/process-groups/$PG/connections" -d "{
  \"revision\": {\"version\": 0},
  \"component\": {
    \"name\": \"seed-to-sink\",
    \"source\": {\"id\": \"$GEN\", \"groupId\": \"$PG\", \"type\": \"PROCESSOR\"},
    \"destination\": {\"id\": \"$UPD\", \"groupId\": \"$PG\", \"type\": \"PROCESSOR\"},
    \"selectedRelationships\": [\"success\"]
  }
}" | jq -r '.id')
echo "connection: $CONN"

CS=$(nifi POST "/process-groups/$PG/controller-services" -d '{
  "revision": {"version": 0},
  "component": {"type": "org.apache.nifi.csv.CSVReader", "name": "probe-reader"}
}' | jq -r '.id')
CS_REV=$(nifi GET "/controller-services/$CS" | jq -r '.revision.version')
nifi PUT "/controller-services/$CS/run-status" -d "{
  \"revision\": {\"version\": $CS_REV}, \"state\": \"ENABLED\"
}" | jq -r '"controller service: \(.id) -> \(.component.state)"'

jq -n --arg pg "$PG" --arg gen "$GEN" --arg upd "$UPD" --arg conn "$CONN" --arg cs "$CS" \
  '{pg: $pg, gen: $gen, upd: $upd, conn: $conn, cs: $cs}' | tee "$STATE"
