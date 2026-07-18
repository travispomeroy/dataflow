#!/usr/bin/env bash
# M1 exit gates — the single verification seam for milestone M1 (docs/PLAN.md).
# The exit code is the verdict: 0 = gates pass. Run from anywhere.
#
# Later milestone tickets extend the numbered stages below; append checks, don't
# restructure. Clean-machine prerequisites: unchanged from M0 (Docker, Node, git, JDK).
set -euo pipefail

cd "$(dirname "$0")/.."
stage() { printf '\n== %s\n' "$*"; }

# -- Stage 1: all previous gates still pass -----------------------------------
# Chaining M0 first (standing rule) is also what makes the walkthrough
# hermetic: M0 recreates the compose world from nothing, so the dataflow
# database starts empty, and its `./mvnw verify` — which now carries M1's
# compiler snapshot goldens, canonical-example round-trip, and Modulith
# boundary tests — leaves a fresh executable jar for Stage 3. Its ui build
# (`nx run-many -t typecheck build`) already type-checks every Nx project.
stage "Stage 1: M0 gates still pass (builds + world recreated from nothing)"
e2e/m0-gates.sh

# -- Stage 2: the TS mirror seam, named explicitly ----------------------------
# Redundant with Stage 1's run-many on purpose: the mirror holding against the
# canonical examples is an M1 exit gate in its own right, so name it.
stage "Stage 2: TS mirror type-checks the canonical examples"
(cd ui && npx nx typecheck dataflow-config)

# -- Stage 3: the control plane comes up against the running world ------------
# The control plane is not a compose service (it is the app under development);
# the gate runs the executable jar Stage 1 built. Its defaults match the
# committed mock-world infra/.env, so no env is injected here.
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
cp_log=$(mktemp)
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
  rm -f "$cp_log"
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

# -- Stage 4: the product surface -----------------------------------------
# One Dataflow through its whole lifecycle, negative paths included; the
# walkthrough asserts API responses, Kestra state, and the golden plan only.
stage "Stage 4: REST walkthrough (draft → invalid deploy → deploy → honest FAILED → undeploy → delete)"
node --env-file=infra/.env e2e/m1-walkthrough.mjs

stage "M1 gates: PASS (world left running; control plane stopped)"
