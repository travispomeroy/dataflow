#!/usr/bin/env bash
# Q7 — full teardown. FINDING (first run): deleting a parameter context while a
# PG is still bound to it SUCCEEDS (HTTP 200) — NiFi does not protect the
# binding. Order therefore doesn't matter for teardown; delete both, verify
# absent. (Sequenced PG-first here anyway, as the runner will do.)
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
PG=$(jq -r .uploadedPg "${TMPDIR:-/tmp}/m4.1-uploaded.json")
CTX=$(jq -r .ctx "${TMPDIR:-/tmp}/m4.1-ctx.json")

echo "--- delete uploaded PG (never started: no queue, services disabled) ---"
REV=$(nifi GET "/process-groups/$PG" | jq -r '.revision.version')
curl -sk -o /dev/null -w 'DELETE pg: HTTP %{http_code}\n' -X DELETE \
  "$NIFI_API/process-groups/$PG?version=$REV&clientId=m41-spike" \
  -H "Authorization: Bearer $(token)"

echo "--- delete context ---"
REV=$(nifi GET "/parameter-contexts/$CTX" | jq -r '.revision.version')
curl -sk -o /dev/null -w 'DELETE context: HTTP %{http_code}\n' -X DELETE \
  "$NIFI_API/parameter-contexts/$CTX?version=$REV&clientId=m41-spike" \
  -H "Authorization: Bearer $(token)"

echo "--- verify absent ---"
curl -sk -o /dev/null -w 'GET pg: HTTP %{http_code}\n' "$NIFI_API/process-groups/$PG" -H "Authorization: Bearer $(token)"
curl -sk -o /dev/null -w 'GET context: HTTP %{http_code}\n' "$NIFI_API/parameter-contexts/$CTX" -H "Authorization: Bearer $(token)"
echo "root child PGs: $(nifi GET /flow/process-groups/root | jq '.processGroupFlow.flow.processGroups | length')"
echo "contexts remaining: $(nifi GET /flow/parameter-contexts | jq '.parameterContexts | length')"
