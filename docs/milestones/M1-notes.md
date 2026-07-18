# M1 — Domain core: as-built notes

Spec: issue #10; tickets #11–#18. Everything below is what deviated from the plan, was
decided mid-flight, or will bite the next person if forgotten. What went exactly to plan
is not repeated here — see `docs/PLAN.md` M1, the spec, and the M1 commit bodies (each
records its ticket's own load-bearing decisions).

## Deviations from plan / spec

- **Catalog seeds live inside the catalog module, not `infra/seeds`**: the plan's
  original sketch had seed files under `infra/`; they are committed resources inside the
  `catalog` module instead, so the module's API stays the only seam and a catalog change
  is a reviewable diff next to the code that reads it. (Settled in the spec, recorded
  here as the plan deviation it is.)
- **The walkthrough is a Node script, not literal curl**: the spec says "scripted curl
  walkthrough"; `e2e/m1-walkthrough.mjs` uses Node's built-in `fetch` (the `smoke.mjs`
  prior art) because JSON assertions in bash need `jq`, which is not an M0 prerequisite.
  Unlike smoke's independent checks it is deliberately sequential and fail-fast — each
  step depends on the state the previous one built.
- **`m1-gates.sh` chains `m0-gates.sh` first, not last**: chaining M0 up front recreates
  the world from nothing (walkthrough starts against an empty dataflow database) and its
  `./mvnw verify` — which now carries the M1 compiler goldens, canonical round-trip, and
  Modulith boundary tests — leaves the executable jar the M1 stages boot. The spec's
  seams (1) and (2) therefore run *inside* the chain; Stage 2 re-runs the TS-mirror
  typecheck by name (Nx cache makes it free) so the M1 exit gate is explicit.

## Mid-flight decisions

- **The control plane is not a compose service** (deliberate — it is the app under
  development, not mock world): the gate boots the Stage-1 jar itself, polls
  `/api/catalog/sources` for readiness, refuses to run if anything already listens on
  8085 (a stale dev instance would poison the walkthrough with old code), and kills it
  via the script's single EXIT trap.
- **Kestra is configured `full_content` in compose — this is load-bearing.** Kestra
  1.3.28's default streamed request handling has a fatal allocation bug: any request
  whose **body arrives in a later TCP read than its headers** sends the heap from ~1GB
  to its ~3.9GB max within seconds; the GC storm hangs every API call, then
  `OutOfMemoryError` kills the scheduler/worker/queue threads. curl coalesces small
  requests into one segment (never triggers); the JDK HTTP client always flushes headers
  and body separately (triggers, timing-dependent through Docker's loopback proxy) — so
  control-plane deploys detonated Kestra while every hand-probe looked fine. Reproduced
  with a raw socket: one `write()` → instant 404; two `write()`s → detonation.
  `micronaut.server.netty.server-type: full_content` (aggregate the body before
  dispatch) removes the sensitivity entirely; it requires capping
  `micronaut.server.max-request-size` (256MB) because the Netty aggregator takes an
  `int` and Kestra's default overflows it into `maxContentLength: -2147483648`, which
  kills every request at the pipeline.

## Gotchas (will bite again)

- **A wedged Kestra is a zombie, not a corpse**: after the OOM its web server still
  answers (basic auth, flow CRUD, even fast GETs) while the scheduler and workers are
  dead — deploys "work", runs never start, and the compose healthcheck stays green
  because it probes the management port. If runs stop reaching a terminal state, check
  Kestra's log for `OutOfMemoryError` and `docker compose --project-directory infra
  restart kestra`.
- **Run polling budget**: the runs poller is fixed-delay 5s and a run-now execution
  takes Kestra a few seconds to schedule and fail; the walkthrough polls to terminal
  with a 240s ceiling. Do not "fix" a slow gate by asserting a non-terminal state.
- **The honest FAILED is the assertion**: the placeholder engine stages nothing, so
  `staging_pull` fails and the Run must end `FAILED`. A `SUCCEEDED` here means someone
  rigged the placeholder — the walkthrough fails loudly on it. M2 swaps the placeholder
  for a real engine task and flips this expectation.
- **`delivered-files` is a text column holding `[]` until M2** promotes it to jsonb with
  one `USING` cast when the document has a real shape.
- **JDK HttpClient ↔ Kestra Netty quirks**: the client pins HTTP/1.1 (Kestra never
  answers the cleartext h2c upgrade; without the pin every write hangs forever) — see
  `RestKestraClient` javadoc.

## State at close

All M0 + M1 gates green via `e2e/m1-gates.sh` on a from-nothing world (world left
running, control plane stopped). One Dataflow walked the full lifecycle: half-built save
→ structural/semantic 422s → deploy v1 (flow live in Kestra, `dataflow.version: "1"`)
→ run-now → terminal FAILED with timing and execution id → history → 409 on
delete-while-deployed → undeploy (flow gone, snapshot retained) → delete. Fixtures
remain untouched by M1; they freeze when M2 goldens land.
