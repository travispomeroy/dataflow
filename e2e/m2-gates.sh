#!/usr/bin/env bash
# M2 exit gates — the single verification seam for milestone M2 (docs/PLAN.md).
# The exit code is the verdict: 0 = gates pass. Run from anywhere.
#
# Later milestone tickets extend the numbered stages below; append checks, don't
# restructure. Clean-machine prerequisites: unchanged from M0 (Docker, Node, git, JDK).
set -euo pipefail

cd "$(dirname "$0")/.."
stage() { printf '\n== %s\n' "$*"; }

# -- Stage 1: all previous gates still pass -----------------------------------
# The M1 chain recreates the world from nothing (M0), runs `./mvnw verify` with
# the M2 compiler snapshot goldens (.hpl, .hwf, flow YAML with the embedded
# pipeline, the collapse-rule plan), and walks the full lifecycle — including
# a run to SUCCEEDED, which M2.5 flipped from the M1 placeholder's honest
# FAILED. It leaves the world running, the jar built, the control plane stopped.
stage "Stage 1: M0+M1 gates still pass (world recreated from nothing, run to SUCCEEDED)"
e2e/m1-gates.sh

# -- Stage 2: the oracle regenerates byte-identically --------------------------
# Mirrors the fixture-regen gate: the committed goldens must be exactly what
# the independent oracle computes from the (frozen) fixtures today. A dirty
# diff means either the oracle drifted or someone hand-edited a golden.
stage "Stage 2: oracle-regen — golden delivered CSVs are byte-stable"
rm -rf e2e/golden/delivered
node e2e/oracle.mjs
if [[ -n "$(git status --porcelain -- e2e/golden/delivered)" ]]; then
  git status --porcelain -- e2e/golden/delivered >&2
  git --no-pager diff -- e2e/golden/delivered | head -60 >&2
  echo "FAIL: regenerated golden CSVs are not byte-identical to the committed ones" >&2
  exit 1
fi

# -- Stage 3: the control plane comes up again --------------------------------
# Same seam as m1-gates Stage 3 (which stopped its instance on exit): boot the
# Stage-1 jar, refuse a squatter on 8085, poll the catalog for readiness.
stage "Stage 3: control plane starts (executable jar from Stage 1)"
if (exec 3<>/dev/tcp/localhost/8085) 2>/dev/null; then
  echo "FAIL: something already listens on 8085 — a stale control plane would poison the walkthrough" >&2
  exit 1
fi
jar=(control-plane/target/control-plane-*.jar)
if [[ ! -f "${jar[0]}" ]]; then
  echo "FAIL: no executable jar under control-plane/target — did Stage 1 run?" >&2
  exit 1
fi
cp_log=$(mktemp) fetch_dir=$(mktemp -d) sftp_batch=$(mktemp)
java -jar "${jar[0]}" >"$cp_log" 2>&1 &
cp_pid=$!
# the script's single EXIT trap — later stages must extend it, not re-trap
cleanup() {
  local status=$?
  kill "$cp_pid" 2>/dev/null || true
  wait "$cp_pid" 2>/dev/null || true
  if [[ $status -ne 0 ]]; then
    echo "-- control plane log (tail) --" >&2
    tail -50 "$cp_log" >&2
  fi
  rm -rf "$cp_log" "$fetch_dir" "$sftp_batch"
}
trap cleanup EXIT

ready=""
for _ in $(seq 1 90); do
  if ! kill -0 "$cp_pid" 2>/dev/null; then
    echo "FAIL: the control plane exited during startup" >&2
    exit 1
  fi
  if node -e "fetch('http://localhost:8085/api/catalog/sources').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))" 2>/dev/null; then
    ready=yes
    break
  fi
  sleep 1
done
if [[ -z "$ready" ]]; then
  echo "FAIL: the control plane did not answer /api/catalog/sources within 90s" >&2
  exit 1
fi

# -- Stage 4: deploy → run-now → SUCCEEDED → run history -----------------------
# The canonical Positions Feed, deployed fresh and run with the pinned Business
# Date; the walkthrough polls to terminal and pins the run record against the
# golden names and counts. Its delivery is what Stage 5 fetches.
stage "Stage 4: M2 walkthrough (deploy → run businessDate=2026-07-17 → SUCCEEDED → history)"
node e2e/m2-walkthrough.mjs

# -- Stage 5: the delivered bytes, fetched back off the wire -------------------
# The point of the milestone: what Pomeroy Provider's SFTP directory actually
# holds byte-matches the independent oracle's goldens — engine and oracle agree.
# Same non-interactive sftp posture as the M0 probe (committed host key, no
# known_hosts outside the repo); -b aborts on the first failing get, so a
# missing file fails the gate before any compare runs.
stage "Stage 5: five files fetched from SFTP byte-match the oracle goldens"
printf 'cd upload/pomeroy\n' > "$sftp_batch"
for golden in e2e/golden/delivered/*.csv; do
  printf 'get %s %s/\n' "$(basename "$golden")" "$fetch_dir" >> "$sftp_batch"
done
SFTP_PASSWORD=$(grep '^SFTP_PASSWORD=' infra/.env | cut -d= -f2-) \
SSH_ASKPASS="$PWD/infra/sftp/askpass.sh" SSH_ASKPASS_REQUIRE=force \
  sftp -P 2222 \
    -o BatchMode=no \
    -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$PWD/infra/sftp/known_hosts" \
    -o GlobalKnownHostsFile=/dev/null \
    -o PreferredAuthentications=password \
    -b "$sftp_batch" pomeroy@localhost
for golden in e2e/golden/delivered/*.csv; do
  name=$(basename "$golden")
  if ! cmp "$golden" "$fetch_dir/$name"; then
    diff "$golden" "$fetch_dir/$name" | head -20 >&2 || true
    echo "FAIL: delivered $name differs from the oracle golden" >&2
    exit 1
  fi
done

stage "M2 gates: PASS (world left running; control plane stopped)"
