#!/usr/bin/env bash
# Q3 — what blocks deleting a process group, and the required cleanup ordering.
# The probe PG has an ENABLED CSVReader. Add a queued flowfile (RUN_ONCE) and a
# running processor, then attempt the delete at each stage of cleanup.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
STATE="${TMPDIR:-/tmp}/m4.1-probe.json"
PG=$(jq -r .pg "$STATE"); GEN=$(jq -r .gen "$STATE"); CS=$(jq -r .cs "$STATE")

attempt_delete() {
  local rev; rev=$(nifi GET "/process-groups/$PG" | jq -r '.revision.version')
  echo ">>> DELETE attempt ($1):"
  curl -sk -o /dev/stdout -w '\n[HTTP %{http_code}]\n' -X DELETE \
    "$NIFI_API/process-groups/$PG?version=$rev&clientId=m41-spike" \
    -H "Authorization: Bearer $(token)"
}

echo "--- arrange: queued flowfile + running seed ---"
REV=$(nifi GET "/processors/$GEN" | jq -r '.revision.version')
nifi PUT "/processors/$GEN/run-status" -d "{\"revision\":{\"version\":$REV},\"state\":\"RUN_ONCE\"}" >/dev/null
sleep 2
REV=$(nifi GET "/processors/$GEN" | jq -r '.revision.version')
nifi PUT "/processors/$GEN/run-status" -d "{\"revision\":{\"version\":$REV},\"state\":\"RUNNING\"}" >/dev/null
echo "queued: $(nifi GET "/flow/process-groups/$PG/status" | jq -r '.processGroupStatus.aggregateSnapshot.flowFilesQueued'), seed RUNNING, reader ENABLED"

attempt_delete "running processor + queued data + enabled service"

echo "--- stop the whole PG ---"
nifi PUT "/flow/process-groups/$PG" -d "{\"id\":\"$PG\",\"state\":\"STOPPED\"}" | jq -r '"pg state -> \(.state)"'
attempt_delete "stopped, but queued data + enabled service"

echo "--- drop all queues ---"
DROP=$(nifi POST "/process-groups/$PG/empty-all-connections-requests" -d '' | jq -r '.dropRequest.id')
sleep 1
nifi DELETE "/process-groups/$PG/empty-all-connections-requests/$DROP" >/dev/null
attempt_delete "stopped + drained, but enabled service"

echo "--- disable all controller services in the PG ---"
nifi PUT "/flow/process-groups/$PG/controller-services" \
  -d "{\"id\":\"$PG\",\"state\":\"DISABLED\",\"disconnectedNodeAcknowledged\":false}" | jq -r '"services -> \(.state)"'
sleep 2
echo "reader state: $(nifi GET "/controller-services/$CS" | jq -r '.component.state')"
attempt_delete "stopped + drained + disabled"

echo "--- verify absent ---"
curl -sk -o /dev/null -w 'GET deleted pg: HTTP %{http_code}\n' \
  "$NIFI_API/process-groups/$PG" -H "Authorization: Bearer $(token)"
