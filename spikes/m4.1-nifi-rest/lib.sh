# M4.1 spike helper — NOT production code. Sourced by the numbered scripts.
# Findings live on issue #38; these scripts exist so the findings are re-runnable.
set -euo pipefail

SPIKE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SPIKE_DIR/../.." && pwd)"
NIFI_API="https://localhost:8443/nifi-api"

# shellcheck disable=SC1091
source "$REPO_ROOT/infra/.env" 2>/dev/null || true

TOKEN_FILE="${TMPDIR:-/tmp}/m4.1-nifi-token"

fetch_token() {
  local status
  status=$(curl -sk -o "$TOKEN_FILE" -w '%{http_code}' -X POST "$NIFI_API/access/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "username=$NIFI_USER" \
    --data-urlencode "password=$NIFI_PASSWORD")
  echo "token: HTTP $status, $(wc -c <"$TOKEN_FILE" | tr -d ' ') chars cached to $TOKEN_FILE" >&2
  [ "$status" = "201" ]
}

token() { cat "$TOKEN_FILE"; }

# nifi <method> <path> [curl args...] — authenticated JSON call, prints body
nifi() {
  local method="$1" path="$2"; shift 2
  curl -sk -X "$method" "$NIFI_API$path" \
    -H "Authorization: Bearer $(token)" \
    -H 'Content-Type: application/json' "$@"
}

root_pg_id() { nifi GET /flow/process-groups/root | jq -r '.processGroupFlow.id'; }
