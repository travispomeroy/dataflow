#!/usr/bin/env bash
# Q2 (part 2) — the identifier-preservation experiment.
# Rewrite the downloaded snapshot to carry compiler-style minted identifiers
# (recognizable aaaaaaaa-... UUIDs), strip every instanceIdentifier, upload it
# as a new PG in one REST call, then inspect what ids the live components got.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
STATE="${TMPDIR:-/tmp}/m4.1-probe.json"
IN="${TMPDIR:-/tmp}/m4.1-download.json"
MINTED="${TMPDIR:-/tmp}/m4.1-minted.json"

# strip instanceIdentifiers everywhere — a compiled artifact would never have them
jq 'walk(if type == "object" then del(.instanceIdentifier) else . end)' "$IN" > "$MINTED.tmp"

# replace each exported identifier with a recognizable minted UUID, everywhere it
# appears (component identifier, groupIdentifier, connection source/destination)
i=1
for old in $(jq -r '[.. | objects | .identifier? // empty] | unique | .[]' "$MINTED.tmp"); do
  new=$(printf 'aaaaaaaa-0000-4000-8000-%012d' "$i")
  echo "mint: $old -> $new"
  sed -i '' "s/$old/$new/g" "$MINTED.tmp"
  i=$((i+1))
done
mv "$MINTED.tmp" "$MINTED"

ROOT=$(root_pg_id)
echo "--- upload ---"
UP=$(curl -sk -X POST "$NIFI_API/process-groups/$ROOT/process-groups/upload" \
  -H "Authorization: Bearer $(token)" \
  -F 'groupName=m41-uploaded' -F 'positionX=600' -F 'positionY=0' \
  -F 'clientId=m41-spike' -F 'disconnectedNodeAcknowledged=false' \
  -F "file=@$MINTED;type=application/json")
NEWPG=$(echo "$UP" | jq -r '.id')
echo "uploaded pg id: $NEWPG (minted pg identifier was aaaaaaaa-...)"
echo "$UP" | jq '{id, versionedId: .component.versionedComponentId?}'

echo "--- live processors: id vs versionedComponentId ---"
nifi GET "/process-groups/$NEWPG/processors" \
  | jq '.processors[] | {name: .component.name, liveId: .id, versionedComponentId: .component.versionedComponentId}'

echo "--- live connections ---"
nifi GET "/process-groups/$NEWPG/connections" \
  | jq '.connections[] | {liveId: .id, versionedComponentId: .component.versionedComponentId, sourceLiveId: .component.source.id, destLiveId: .component.destination.id}'

echo "--- live controller services ---"
nifi GET "/flow/process-groups/$NEWPG/controller-services" \
  | jq '.controllerServices[] | {name: .component.name, liveId: .id, versionedComponentId: .component.versionedComponentId, state: .component.state}'

jq -r .pg "$STATE" | xargs -I{} echo "probe pg still: {}"
jq -n --arg pg "$NEWPG" '{uploadedPg: $pg}' > "${TMPDIR:-/tmp}/m4.1-uploaded.json"
