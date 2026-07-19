#!/usr/bin/env bash
# Q2 (part 3) — upload the SAME minted artifact a second time: are re-minted
# live ids stable per upload, or fresh each time?
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
MINTED="${TMPDIR:-/tmp}/m4.1-minted.json"
ROOT=$(root_pg_id)
UP=$(curl -sk -X POST "$NIFI_API/process-groups/$ROOT/process-groups/upload" \
  -H "Authorization: Bearer $(token)" \
  -F 'groupName=m41-uploaded-2' -F 'positionX=1200' -F 'positionY=0' \
  -F 'clientId=m41-spike' -F 'disconnectedNodeAcknowledged=false' \
  -F "file=@$MINTED;type=application/json")
PG2=$(echo "$UP" | jq -r '.id')
echo "second upload pg: $PG2"
nifi GET "/process-groups/$PG2/processors" \
  | jq '.processors[] | {name: .component.name, liveId: .id, versionedComponentId: .component.versionedComponentId}'
# throwaway copy — delete immediately (no queued data, services disabled, nothing running)
REV=$(nifi GET "/process-groups/$PG2" | jq -r '.revision.version')
nifi DELETE "/process-groups/$PG2?version=$REV&clientId=m41-spike" | jq -r '"deleted second copy: \(.id)"'
