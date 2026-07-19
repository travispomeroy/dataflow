# e2e/lib/boot-control-plane.sh — the shared control-plane boot seam.
#
# Sourced by gate scripts, never executed: the caller has already `cd`ed to the
# repo root and set -euo pipefail. Extracted from the m1/m2 gates' Stage 3
# (M3.7) — same probes, messages, and trap semantics; only `jar`/`ready` became
# locals and m2's hardcoded cleanup list became the cp_cleanup_paths array.
# Before this file, the block was copied between gates and a startup-seam
# change had to land in each copy.
#
# boot_control_plane: refuses a squatter on 8085 (a stale control plane would
# poison the walkthrough), boots the executable jar the chained builds left
# under control-plane/target, installs the script's single EXIT trap, and polls
# /api/catalog/sources until the control plane answers (90s budget).
#
# After it returns:
#   cp_pid, cp_log    — the running control plane and its captured log
#   cp_cleanup_paths  — array the EXIT trap rm -rf's; a gate appends its own
#                       temp paths here instead of re-trapping
#
# The trap kills the control plane, tails its log when the script is failing,
# and removes every path in cp_cleanup_paths. Later stages extend cleanup via
# the array — never re-trap.

boot_control_plane() {
  if (exec 3<>/dev/tcp/localhost/8085) 2>/dev/null; then
    echo "FAIL: something already listens on 8085 — a stale control plane would poison the walkthrough" >&2
    exit 1
  fi
  local jar
  jar=(control-plane/target/control-plane-*.jar)
  if [[ ! -f "${jar[0]}" ]]; then
    echo "FAIL: no executable jar under control-plane/target — did Stage 1 run?" >&2
    exit 1
  fi
  cp_log=$(mktemp)
  cp_cleanup_paths=("$cp_log")
  java -jar "${jar[0]}" >"$cp_log" 2>&1 &
  cp_pid=$!
  trap _boot_control_plane_cleanup EXIT

  local ready=""
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
}

_boot_control_plane_cleanup() {
  local status=$?
  kill "$cp_pid" 2>/dev/null || true
  wait "$cp_pid" 2>/dev/null || true
  if [[ $status -ne 0 ]]; then
    echo "-- control plane log (tail) --" >&2
    tail -50 "$cp_log" >&2
  fi
  rm -rf "${cp_cleanup_paths[@]}"
}
