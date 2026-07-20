#!/usr/bin/env bash
# M4 exit gates — the single verification seam for milestone M4 (docs/PLAN.md).
# The exit code is the verdict: 0 = gates pass. Run from anywhere.
#
# Later milestone tickets extend the numbered stages below; append checks, don't
# restructure. Clean-machine prerequisites: unchanged from M0 (Docker, Node,
# git, JDK) — Chromium is installed by the M3 chain, the curl+jq driver image is
# pre-pulled by the M0 chain, both version-locked by their pins.
set -euo pipefail

cd "$(dirname "$0")/.."
stage() { printf '\n== %s\n' "$*"; }
source e2e/lib/boot-control-plane.sh

# -- Stage 1: all previous gates still pass -----------------------------------
# The M3 chain recreates the world from nothing (M0), re-proves the M1/M2/M3
# lifecycle — including the browser scenario and the SFTP byte-compare — and
# leaves the world running, the jar built, the curl+jq driver image pulled, the
# control plane stopped. Nothing in M4 changed a fixture or a delivered golden,
# so this chain's oracle-regen and byte-compare stages are the guard that they
# didn't: a stray change fails here, before M4's own stages run.
stage "Stage 1: M0+M1+M2+M3 gates still pass (world recreated from nothing, bytes verified)"
e2e/m3-gates.sh

# -- Stage 2: the control plane comes up again --------------------------------
# Same shared seam as the m1/m2/m3 gates: boot the Stage-1 jar, refuse a squatter
# on 8085, poll the catalog for readiness. The seam owns the script's single
# EXIT trap; later stages' temp paths join its cleanup via cp_cleanup_paths.
stage "Stage 2: control plane starts (executable jar from Stage 1)"
boot_control_plane

# -- Stage 3: world hygiene — clear any leftover positions-feed Draft ----------
# The M4 walkthrough creates a "Positions Feed" and the slug is minted from the
# name, so its create would 409 against a leftover from an interrupted earlier
# run (the M3 chain deletes its own, but a crashed run may not have). Undeploy-
# first keeps the stage honest even against a deployed leftover. Gate plumbing,
# not scenario — the same posture as m3-gates.sh Stage 4.
stage "Stage 3: world hygiene — delete any leftover positions-feed Dataflow"
node --input-type=module -e '
const api = "http://localhost:8085/api";
const dataflows = await (await fetch(`${api}/dataflows`)).json();
for (const dataflow of dataflows.filter((d) => d.slug === "positions-feed")) {
  if (dataflow.activeDeploymentVersion !== null) {
    await fetch(`${api}/dataflows/${dataflow.id}/undeploy`, { method: "POST" });
  }
  const res = await fetch(`${api}/dataflows/${dataflow.id}`, { method: "DELETE" });
  if (res.status !== 204) {
    console.error(`FAIL: could not delete leftover Dataflow ${dataflow.id} (${res.status})`);
    process.exit(1);
  }
  console.log(`deleted leftover Dataflow ${dataflow.id}`);
}
'

# -- Stage 4: the matrix — same feed, both engines, identical bytes -----------
# The point of the milestone: deploy the canonical Positions Feed with
# engine=nifi → run to SUCCEEDED → fetch the five CSVs off Pomeroy Provider's
# SFTP and byte-compare against the existing M2 goldens → flip to engine=hop,
# redeploy → assert the process group and parameter context are gone from NiFi
# (supersession teardown) → rerun → byte-compare again → undeploy and delete.
# Identical bytes from a second engine *is* the milestone. NIFI_* and
# SFTP_PASSWORD come from infra/.env, as the m0 smoke and m1 walkthrough are run.
stage "Stage 4: M4 walkthrough (nifi deploy → SUCCEEDED → bytes → flip to hop → teardown proven → bytes)"
node --env-file=infra/.env e2e/m4-walkthrough.mjs

stage "M4 gates: PASS (world left running; control plane stopped)"
