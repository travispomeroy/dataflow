#!/usr/bin/env bash
# Q1 — token auth: mint a token, decode its JWT claims, prove reuse.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

fetch_token
echo "--- JWT payload (lifetime claims) ---"
# JWT payload is base64url without padding; pad before decoding
payload=$(token | cut -d. -f2 | tr '_-' '/+')
pad=$(( (4 - ${#payload} % 4) % 4 ))
printf '%s' "$payload$(printf '=%.0s' $(seq 1 $pad 2>/dev/null))" | base64 -d 2>/dev/null | jq .

echo "--- reuse: three authenticated calls on the same token ---"
for i in 1 2 3; do
  code=$(nifi GET /flow/about -o /dev/null -w '%{http_code}')
  echo "call $i: HTTP $code"
done
nifi GET /flow/about | jq -r '.about.version'
