#!/usr/bin/env bash
# M0 exit gates — the single verification seam for milestone M0 (docs/PLAN.md).
# The exit code is the verdict: 0 = gates pass. Run from anywhere.
#
# Later M0 tickets extend the numbered stages below; append checks, don't
# restructure. Clean-machine prerequisites: Docker, Node, git (M0.8 adds JDK).
set -euo pipefail

cd "$(dirname "$0")/.."
compose() { docker compose --project-directory infra "$@"; }
stage() { printf '\n== %s\n' "$*"; }

# Fail with a clear message on a too-old Node instead of a confusing flag error
required_node_major=$(cut -d. -f1 .nvmrc)
running_node_major=$(node --version | tr -d v | cut -d. -f1)
if (( running_node_major < required_node_major )); then
  echo "FAIL: Node $(node --version) is older than the pinned major in .nvmrc ($(cat .nvmrc))" >&2
  exit 1
fi

# -- Stage 1: scaffold builds -------------------------------------------------
# (M0.7 adds the Nx build of the empty app; M0.8 adds ./mvnw verify.)

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

stage "M0 gates: PASS (world left running)"
