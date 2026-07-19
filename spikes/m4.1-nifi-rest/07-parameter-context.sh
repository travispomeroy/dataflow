#!/usr/bin/env bash
# Q4 — parameter context: create with a plain and a sensitive parameter, bind to
# the uploaded PG, drive the async update-request API to completion, confirm the
# sensitive value is write-only on read-back.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
PG=$(jq -r .uploadedPg "${TMPDIR:-/tmp}/m4.1-uploaded.json")

echo "--- create context (plain + sensitive) ---"
CTX=$(nifi POST "/parameter-contexts" -d '{
  "revision": {"version": 0},
  "component": {
    "name": "m41-ctx",
    "parameters": [
      {"parameter": {"name": "businessDate", "sensitive": false, "value": "2026-07-17"}},
      {"parameter": {"name": "minioSecret", "sensitive": true, "value": "super-secret-value"}}
    ]
  }
}')
CTX_ID=$(echo "$CTX" | jq -r '.id')
echo "context: $CTX_ID"

echo "--- read-back: sensitive must be write-only ---"
nifi GET "/parameter-contexts/$CTX_ID" \
  | jq '.component.parameters[].parameter | {name, sensitive, value}'

echo "--- bind context to uploaded PG ---"
PG_REV=$(nifi GET "/process-groups/$PG" | jq -r '.revision.version')
nifi PUT "/process-groups/$PG" -d "{
  \"revision\": {\"version\": $PG_REV},
  \"component\": {\"id\": \"$PG\", \"parameterContext\": {\"id\": \"$CTX_ID\"}}
}" | jq -r '"bound: pg \(.id) -> context \(.component.parameterContext.id)"'

echo "--- async update: change businessDate, rotate the sensitive value ---"
CTX_REV=$(nifi GET "/parameter-contexts/$CTX_ID" | jq -r '.revision.version')
REQ=$(nifi POST "/parameter-contexts/$CTX_ID/update-requests" -d "{
  \"revision\": {\"version\": $CTX_REV},
  \"id\": \"$CTX_ID\",
  \"component\": {
    \"id\": \"$CTX_ID\",
    \"parameters\": [
      {\"parameter\": {\"name\": \"businessDate\", \"sensitive\": false, \"value\": \"2026-07-18\"}},
      {\"parameter\": {\"name\": \"minioSecret\", \"sensitive\": true, \"value\": \"rotated-secret\"}}
    ]
  }
}")
REQ_ID=$(echo "$REQ" | jq -r '.request.requestId')
echo "update request: $REQ_ID (state: $(echo "$REQ" | jq -r '.request.state'))"

for i in $(seq 1 15); do
  R=$(nifi GET "/parameter-contexts/$CTX_ID/update-requests/$REQ_ID")
  DONE=$(echo "$R" | jq -r '.request.complete')
  echo "poll $i: complete=$DONE state=$(echo "$R" | jq -r '.request.state') failure=$(echo "$R" | jq -r '.request.failureReason')"
  [ "$DONE" = "true" ] && break
  sleep 1
done
nifi DELETE "/parameter-contexts/$CTX_ID/update-requests/$REQ_ID" \
  | jq -r '"request deleted, final: \(.request.state)"'

echo "--- values after update ---"
nifi GET "/parameter-contexts/$CTX_ID" \
  | jq '.component.parameters[].parameter | {name, value}'

jq -n --arg ctx "$CTX_ID" '{ctx: $ctx}' > "${TMPDIR:-/tmp}/m4.1-ctx.json"
