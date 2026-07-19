#!/usr/bin/env bash
# Q2 (part 1) — download the probe PG's flow definition snapshot and inspect
# which identifier fields the export carries.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
STATE="${TMPDIR:-/tmp}/m4.1-probe.json"
PG=$(jq -r .pg "$STATE")
OUT="${TMPDIR:-/tmp}/m4.1-download.json"

nifi GET "/process-groups/$PG/download" > "$OUT"
echo "saved: $OUT ($(wc -c <"$OUT" | tr -d ' ') bytes)"

jq '{
  topLevel: keys,
  flowKeys: (.flowContents | keys),
  processor0: (.flowContents.processors[0] | {identifier, instanceIdentifier, name, groupIdentifier}),
  connection0: (.flowContents.connections[0] | {identifier, instanceIdentifier, source, destination}),
  cs0: (.flowContents.controllerServices[0] | {identifier, instanceIdentifier, name, state: .scheduledState})
}' "$OUT"
