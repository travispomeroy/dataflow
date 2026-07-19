#!/usr/bin/env bash
# M3 exit gates — the single verification seam for milestone M3 (docs/PLAN.md).
# The exit code is the verdict: 0 = gates pass. Run from anywhere.
#
# Later milestone tickets extend the numbered stages below; append checks, don't
# restructure. Clean-machine prerequisites: unchanged from M0 (Docker, Node,
# git, JDK) — Chromium is installed by Stage 2, version-locked by the pinned
# @playwright/test.
set -euo pipefail

cd "$(dirname "$0")/.."
stage() { printf '\n== %s\n' "$*"; }
source e2e/lib/boot-control-plane.sh

# -- Stage 1: all previous gates still pass -----------------------------------
# The M2 chain recreates the world from nothing (M0), re-proves the M1
# lifecycle walkthrough and the M2 deliver-and-byte-compare, and leaves the
# world running, the jar built, the control plane stopped. Its walkthrough
# also leaves its undeployed "Positions Feed" Draft behind — Stage 4 clears it.
stage "Stage 1: M0+M1+M2 gates still pass (world recreated from nothing, bytes verified)"
e2e/m2-gates.sh

# -- Stage 2: the browser the pinned Playwright drives ------------------------
# Chromium only (spec #28). The package pins the exact browser build; install
# is a no-op once that build is cached, so the gate stays idempotent.
stage "Stage 2: Chromium installed for the pinned @playwright/test (idempotent)"
(cd ui && npx playwright install chromium)

# -- Stage 3: the control plane comes up again --------------------------------
# Same shared seam as the m1/m2 gates: boot the Stage-1 jar, refuse a squatter
# on 8085, poll the catalog for readiness. The seam owns the script's single
# EXIT trap; Stage 6's temp paths join its cleanup via cp_cleanup_paths.
stage "Stage 3: control plane starts (executable jar from Stage 1)"
boot_control_plane
fetch_dir=$(mktemp -d) sftp_batch=$(mktemp)
cp_cleanup_paths+=("$fetch_dir" "$sftp_batch")

# -- Stage 4: world hygiene — the M2 walkthrough's Draft leaves the stage ------
# The m2 walkthrough deliberately leaves its undeployed "Positions Feed" in the
# database; the slug is minted from the name, so the browser scenario's create
# would 409 against it. Deleting it here is gate plumbing, not scenario — the
# scenario itself touches the API not at all. Undeploy-first keeps the stage
# honest even against a deployed leftover from an interrupted earlier run.
stage "Stage 4: world hygiene — delete the M2 walkthrough's leftover positions-feed Draft"
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

# -- Stage 5: the built bundle, driven by a real browser ----------------------
# The point of the milestone: the whole lifecycle — create, compose on the
# canvas, deploy, run with the golden Business Date, watch the delivery land,
# undeploy, delete — through the UI alone. The e2e project's webServer serves
# `vite preview` of the nx build, the analogue of booting the real jar.
stage "Stage 5: browser scenario against the built bundle (nx build web + e2e web-e2e)"
(cd ui && npx nx build web && npx nx e2e web-e2e)

# -- Stage 6: the delivered bytes, fetched back off the wire -------------------
# The UI-triggered run re-delivered over the same golden names, so what the
# Pomeroy Provider SFTP directory holds must still byte-match the oracle's
# goldens — the browser path proves the same bytes, not just a green status.
# Same non-interactive sftp posture as the M0 probe and m2 gate.
stage "Stage 6: five files fetched from SFTP byte-match the oracle goldens"
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

stage "M3 gates: PASS (world left running; control plane stopped)"
