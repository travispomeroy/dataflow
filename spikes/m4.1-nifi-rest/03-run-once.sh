#!/usr/bin/env bash
# Q5 — RUN_ONCE against the stopped seed processor: expect exactly one flowfile
# queued in the downstream connection, and the processor back at STOPPED.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
STATE="${TMPDIR:-/tmp}/m4.1-probe.json"
GEN=$(jq -r .gen "$STATE"); CONN=$(jq -r .conn "$STATE")

REV=$(nifi GET "/processors/$GEN" | jq -r '.revision.version')
echo "seed state before: $(nifi GET "/processors/$GEN" | jq -r '.component.state'), revision $REV"

nifi PUT "/processors/$GEN/run-status" -d "{
  \"revision\": {\"version\": $REV}, \"state\": \"RUN_ONCE\"
}" | jq -r '"run-status accepted -> reported state: \(.component.state)"'

for i in $(seq 1 10); do
  sleep 1
  Q=$(nifi GET "/flow/connections/$CONN/status" | jq -r '.connectionStatus.aggregateSnapshot.flowFilesQueued')
  S=$(nifi GET "/processors/$GEN" | jq -r '.component.state')
  echo "t+${i}s: queued=$Q seed=$S"
done
