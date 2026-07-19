# M3 — UI: the non-technical experience: as-built notes

Spec: issue #28; tickets #29–#36. Everything below is what deviated from the plan, was
decided mid-flight, or will bite the next person if forgotten. What went exactly to plan
is not repeated here — see `docs/PLAN.md` M3, the spec, and the M3 commit bodies.

## Mid-flight decisions

- **The control-plane boot block is shared now, not copied** (M3.7, #35 — supersedes the
  M2 notes' gotcha "The control-plane boot block is copied, not shared"): the block
  reached its third consumer, so `e2e/lib/boot-control-plane.sh` holds the seam exactly
  as the gates had it — 8085 squatter refusal, jar glob, single EXIT trap, readiness
  poll against `/api/catalog/sources`. `m1-gates.sh` and `m2-gates.sh` source it;
  `m3-gates.sh` must too. A gate extends cleanup by appending its own temp paths to
  `cp_cleanup_paths` (the trap `rm -rf`s the array) — never by re-trapping. A
  startup-seam change now lands once.

## Deviations from plan / spec

- **`m3-gates.sh` gained a world-hygiene stage the spec's chain didn't list**: the m2
  walkthrough deliberately leaves its undeployed "Positions Feed" Draft behind, and the
  slug is minted from the name — the browser scenario's create would 409 against it. So
  Stage 4 undeploys (if needed) and deletes any `positions-feed` Dataflow via the API
  before the browser opens. Gate plumbing, not scenario: the Playwright spec itself
  makes no API calls.
- **One builder change rode along (`fitView`)**: the canvas passed `fitView`
  unconditionally, but on a Draft that opens empty React Flow defers fitView until the
  first nodes *measure* — so the first palette **drop** yanked the viewport to max zoom
  (2×), and every later drop landed through that transform, overlapping the nodes.
  Found by the Playwright scenario; users would have hated the jump just as much. The
  canvas now fits the view only when the Draft opens with nodes on it.

## Canvas / Playwright gotchas (the ticket asked for these)

- **Run history follows the slug, not the Dataflow row**: Kestra keys executions by
  flow id (= the slug), and the namespace poller attaches what it finds to whatever
  Dataflow currently owns that slug. Delete a "Positions Feed" and create a new one —
  the old incarnation's runs re-materialize in the new one's history. In the gate chain
  the m1/m2 walkthrough runs are therefore legitimately visible to the browser scenario,
  which pins the **newest row only** (and the same reasoning means a run-history
  assertion can never safely use unscoped text matchers).
- **Never boot the Playwright webServer through a continuous Nx target**: `npx nx
  preview web` hands the actual vite process to the Nx daemon, so Playwright's shutdown
  kills only the CLI wrapper — vite stays behind squatting 4200 and every later run
  refuses to start (`reuseExistingServer: false`, deliberately). The config runs
  `npx vite preview` in `apps/web` directly; the gate's `nx build web` supplies the
  bundle.
- **HTML5 drag-and-drop from the palette works natively** in Chromium under Playwright's
  `dragTo` (the `dataTransfer` payload arrives intact — no dispatchEvent shims). Two
  caveats: drop coordinates are relative to the *target element*, and a point past the
  canvas's edge (~620px wide at the default 1280×720 viewport) silently resolves to
  whatever overlays it — the property panel — which then "intercepts pointer events"
  forever. React Flow edge-drawing is plain pointer events: hover the source handle,
  `mouse.down()`, move (with steps), `mouse.up()` on the target handle.
- **MUI popups share their field's accessible name**: an open Autocomplete's listbox
  carries the same label as its input, so `getByLabel('Clients')` strict-fails the
  moment the popup opens — target `getByRole('combobox', { name: ... })`. Same family:
  role-name matching is substring-based, so the Business Date cell assertion needs
  `exact: true` or it also matches the delivered-files cell (`positions_cash_2026-07-17.csv`).
- **The engine is fast once warm**: the scenario's run-to-SUCCEEDED completes in ~10s
  against a warm hop image (the 240s expect budget is for the from-nothing chain, where
  the m1/m2 walkthroughs have already paid the image warm-up).

## State at close

- `e2e/m3-gates.sh` chains `m2-gates.sh` → Chromium install (idempotent, locked by the
  pinned `@playwright/test` 1.61.1) → shared boot helper → world hygiene → `nx build
  web` + `nx e2e web-e2e` (vite preview of the built bundle, Chromium only) → the M2
  SFTP byte re-verify against `e2e/golden/delivered/`.
- The world is left running, control plane stopped — same posture as m1/m2. The browser
  scenario undeploys and deletes its Dataflow from the card, so no Schedule fires in the
  left-running world (the M2 lesson, now proven through the UI).
- Deliberate non-assertion, per spec: the JSON preview is never byte-compared to
  `e2e/canonical/positions-feed.config.json`.
