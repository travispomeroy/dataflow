#!/usr/bin/env bash
# Q6 — drain observability: PG-wide queued count, per-connection depth by id,
# and the drop-all-queues async request API.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
STATE="${TMPDIR:-/tmp}/m4.1-probe.json"
PG=$(jq -r .pg "$STATE"); CONN=$(jq -r .conn "$STATE")

echo "--- PG-wide status (recursive) ---"
nifi GET "/flow/process-groups/$PG/status?recursive=true" \
  | jq '.processGroupStatus.aggregateSnapshot | {flowFilesQueued, bytesQueued, queued}'

echo "--- per-connection status by connection id ---"
nifi GET "/flow/connections/$CONN/status" \
  | jq '.connectionStatus.aggregateSnapshot | {flowFilesQueued, bytesQueued}'

echo "--- drop-all-queues request ---"
REQ=$(nifi POST "/process-groups/$PG/empty-all-connections-requests" -d '')
echo "$REQ" | jq '.dropRequest | {id, state, finished, current}'
DROP_ID=$(echo "$REQ" | jq -r '.dropRequest.id')

for i in $(seq 1 10); do
  R=$(nifi GET "/process-groups/$PG/empty-all-connections-requests/$DROP_ID")
  FIN=$(echo "$R" | jq -r '.dropRequest.finished')
  echo "poll $i: finished=$FIN state=$(echo "$R" | jq -r '.dropRequest.state') dropped=$(echo "$R" | jq -r '.dropRequest.dropped')"
  [ "$FIN" = "true" ] && break
  sleep 1
done
nifi DELETE "/process-groups/$PG/empty-all-connections-requests/$DROP_ID" | jq -r '"request deleted, final state: \(.dropRequest.state)"'

echo "--- queue after drop ---"
nifi GET "/flow/process-groups/$PG/status?recursive=true" \
  | jq '.processGroupStatus.aggregateSnapshot.flowFilesQueued'
