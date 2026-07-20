#!/usr/bin/env bash
# M0 exit gates — the single verification seam for milestone M0 (docs/PLAN.md).
# The exit code is the verdict: 0 = gates pass. Run from anywhere.
#
# Later M0 tickets extend the numbered stages below; append checks, don't
# restructure. Clean-machine prerequisites: Docker, Node, git, JDK.
set -euo pipefail

cd "$(dirname "$0")/.."
compose() { docker compose --project-directory infra "$@"; }
stage() { printf '\n== %s\n' "$*"; }

# Fail with a clear message on the wrong Node instead of a confusing flag error.
# Exact match, not just the major: engine-strict only guards the npm path, so
# this is what pins the Node that runs the smoke script and fixture generator.
required_node=$(cat .nvmrc)
if [[ "$(node --version)" != "v$required_node" ]]; then
  echo "FAIL: running Node $(node --version) but .nvmrc pins $required_node" >&2
  exit 1
fi

# -- Stage 1: scaffold builds -------------------------------------------------
stage "Stage 1: UI installs from lockfile; empty app typechecks and builds"
# npm ci = clean, lockfile-exact install; ui/.npmrc engine-strict + exact
# engines make a wrong Node/npm toolchain fail here rather than build oddly.
# The build script runs typecheck + vite build — vite alone only strips types,
# so without typecheck a type-broken app would still "build".
npm --prefix ui ci
npm --prefix ui run build

stage "Stage 1: control plane builds; Modulith boundary test is green"
# verify runs the ApplicationModules boundary test — a module-boundary violation
# (or a missing planned module) fails the build, not just a real compile error.
# The wrapper downloads the pinned Maven itself, so only a JDK is required.
(cd control-plane && ./mvnw --batch-mode --no-transfer-progress verify)

# -- Stage 2: the world comes up ----------------------------------------------
stage "Stage 2: recreate the world from nothing; healthchecks are the assertion"
compose down --volumes --remove-orphans
compose up --wait --wait-timeout 300

# -- Stage 3: behavior --------------------------------------------------------
stage "Stage 3: per-database users own exactly their database"
compose exec -T postgres psql -U kestra   -d kestra   -tAc 'select current_user' | grep -qx kestra
compose exec -T postgres psql -U dataflow -d dataflow -tAc 'select current_user' | grep -qx dataflow
if cross_connect=$(compose exec -T postgres psql -U kestra -d dataflow -c 'select 1' 2>&1); then
  echo "FAIL: the kestra user can connect to the dataflow database" >&2
  exit 1
elif ! grep -qi 'permission denied' <<<"$cross_connect"; then
  echo "FAIL: cross-database connect failed, but not at permission time: $cross_connect" >&2
  exit 1
fi

stage "Stage 3: smoke checks (Node built-in fetch)"
node --env-file=infra/.env e2e/smoke.mjs

stage "Stage 3: SFTP login pinned against the committed host key; upload home writable"
# Non-interactive password auth via SSH_ASKPASS (no sshpass on clean machines);
# StrictHostKeyChecking=yes + the committed known_hosts means a changed host
# identity or failed auth exits non-zero, and no known_hosts outside the repo
# is ever consulted or written. The put/rm round-trip proves the chrooted
# upload home is writable.
sftp_probe=$(mktemp) sftp_batch=$(mktemp)
# the script's single EXIT trap — later stages must extend it, not re-trap
trap 'rm -f "$sftp_probe" "$sftp_batch"' EXIT
printf 'm0-gate sftp probe\n' > "$sftp_probe"
# -b aborts on the first failing command, so put/ls/rm failures fail the gate
printf 'cd upload\nput %s probe.txt\nls probe.txt\nrm probe.txt\n' "$sftp_probe" > "$sftp_batch"
# BatchMode=no must precede -b: -b implies BatchMode=yes, which disables
# password auth outright, and ssh keeps the first value an option is given
SFTP_PASSWORD=$(grep '^SFTP_PASSWORD=' infra/.env | cut -d= -f2-) \
SSH_ASKPASS="$PWD/infra/sftp/askpass.sh" SSH_ASKPASS_REQUIRE=force \
  sftp -P 2222 \
    -o BatchMode=no \
    -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$PWD/infra/sftp/known_hosts" \
    -o GlobalKnownHostsFile=/dev/null \
    -o PreferredAuthentications=password \
    -b "$sftp_batch" pomeroy@localhost

stage "Stage 3: Hop image runs and reports its pinned version (throwaway container)"
# Deliberately NO compose service — nothing needs a long-running Hop until M5
# (docs/PLAN.md). The pinned image is proven by running its CLI to completion
# in a throwaway container; a missing/broken image or wrong version exits 1.
hop_image=apache/hop:2.18.1   # version pinned in docs/versions.md
hop_pin=${hop_image#*:}
# Hop's CLIs exit 1 by design after printing the version (verified against
# 2.18.1), so the exit code proves nothing — the assertion is on the output:
# some line must be exactly the pinned version. A missing/broken image emits a
# docker error (or nothing) instead and fails the same grep.
hop_reported=$(docker run --rm --entrypoint /bin/bash "$hop_image" \
  -c 'cd /opt/hop && ./hop-run.sh --version' 2>&1 | tr -d '\r') || true
if ! grep -qx "$hop_pin" <<<"$hop_reported"; then
  echo "FAIL: expected version $hop_pin from $hop_image, got: ${hop_reported:-<no output>}" >&2
  exit 1
fi

stage "Stage 3: NiFi run-driver image answers with curl and jq (throwaway container)"
# M4.4: the compiled NiFi run driver executes in this digest-pinned curl+jq
# image, and Kestra 1.3.28's docker runner cannot *pull* a tag@digest reference
# (docs/versions.md) — this throwaway run proves the pin AND pre-pulls it, so
# the flow's pullPolicy IF_NOT_PRESENT never needs the broken pull path.
driver_image='badouralix/curl-jq:alpine@sha256:e1f1e84c4c23c24d665cd9243dcf7fa531965a0b37b89a64cabca847d834dd62'
driver_reported=$(docker run --rm "$driver_image" sh -c 'curl --version >/dev/null && jq --version' 2>&1 | tr -d '\r') || true
if ! grep -q '^jq-' <<<"$driver_reported"; then
  echo "FAIL: expected curl+jq from $driver_image, got: ${driver_reported:-<no output>}" >&2
  exit 1
fi

stage "Stage 3: Kestra secret encodings in infra/.env match the plain values they derive from"
# Kestra OSS reads secrets as base64-encoded SECRET_* env vars; compose cannot
# encode, so infra/.env commits each value twice (plain + *_B64, M2.5). Comments
# alone cannot stop the pair drifting apart — this check can.
env_value() { grep "^$1=" infra/.env | cut -d= -f2-; }
for pair in \
  MINIO_ROOT_USER:KESTRA_SECRET_MINIO_ACCESS_KEY_B64 \
  MINIO_ROOT_PASSWORD:KESTRA_SECRET_MINIO_SECRET_KEY_B64 \
  SFTP_PASSWORD:KESTRA_SECRET_SFTP_POMEROY_B64 \
  NIFI_USER:KESTRA_SECRET_NIFI_USERNAME_B64 \
  NIFI_PASSWORD:KESTRA_SECRET_NIFI_PASSWORD_B64; do
  plain=${pair%%:*} b64=${pair#*:}
  if [[ "$(printf %s "$(env_value "$plain")" | base64)" != "$(env_value "$b64")" ]]; then
    echo "FAIL: $b64 is not the base64 of $plain — regenerate with: printf %s '<value>' | base64" >&2
    exit 1
  fi
done

stage "Stage 3: fixtures and WireMock stubs regenerate byte-identically"
rm -rf infra/fixtures/data infra/fixtures/wiremock
node infra/fixtures/generate.ts
if [[ -n "$(git status --porcelain -- infra/fixtures)" ]]; then
  git status --porcelain -- infra/fixtures >&2
  git --no-pager diff -- infra/fixtures | head -60 >&2
  echo "FAIL: regenerated fixtures are not byte-identical to the committed ones" >&2
  exit 1
fi

stage "M0 gates: PASS (world left running)"
