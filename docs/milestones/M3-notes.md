# M3 — UI: the non-technical experience: as-built notes

Spec: issue #28. **Milestone in flight** — this file accumulates decisions that must
outlive their tickets; the close-out ritual completes it (deviations, gotchas, state at
close).

## Mid-flight decisions

- **The control-plane boot block is shared now, not copied** (M3.7, #35 — supersedes the
  M2 notes' gotcha "The control-plane boot block is copied, not shared"): the block
  reached its third consumer, so `e2e/lib/boot-control-plane.sh` holds the seam exactly
  as the gates had it — 8085 squatter refusal, jar glob, single EXIT trap, readiness
  poll against `/api/catalog/sources`. `m1-gates.sh` and `m2-gates.sh` source it;
  `m3-gates.sh` must too. A gate extends cleanup by appending its own temp paths to
  `cp_cleanup_paths` (the trap `rm -rf`s the array) — never by re-trapping. A
  startup-seam change now lands once.
