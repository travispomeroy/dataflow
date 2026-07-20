# M4 — NiFi server path: the engine swap: as-built notes

Spec: issue #37; tickets #38–#43. Everything below is what deviated from the plan, was
decided mid-flight, or will bite the next person if forgotten. What went exactly to plan
is not repeated here — see `docs/PLAN.md` M4, the spec, and the M4 commit bodies (each
records its ticket's own load-bearing decisions).

## The loop-legibility comparison (recorded per spec — M5 writeup material)

The obligation the M2 notes' pagination finding created: record how NiFi's generated page
loop compares to Hop's `probe → clone → sequence` shape. Both compilers generate the
loop from the Execution Plan alone (Source physical composition stays hidden Catalog
knowledge, never engine-compiler lore — user story #13), and both enforce the same
invariant — a dropped page is impossible by construction, visible in the delivered bytes.
They reach it with opposite graph shapes.

- **Hop is acyclic — a data-parallel fan-out.** Probe page 1 → `JsonInput` reads
  `totalPages` from the envelope → `CloneRow` materializes one row per page → a per-branch
  **named** `Sequence` numbers them → a URL-from-field REST step fetches each page. There
  is no back-edge; the "loop" is N rows flowing through in parallel, and reassembly is
  implicit in the row stream. Termination is arithmetic (`CloneRow` count = `totalPages`).

- **NiFi is genuinely cyclic — a sequential cursor self-loop.** Seed `GenerateFlowFile`
  (page=1, DISABLED, RUN_ONCE at run time) → `InvokeHTTP` fetches `?page=${page}` →
  `EvaluateJsonPath` reads `totalPages` → `UpdateAttribute` stamps `fragment.*` attributes
  → `RouteOnAttribute` (`${page:lt(${totalPages})}` → `next`) → `UpdateAttribute`
  increments the cursor → **back to fetch**. The payload branch feeds a `MergeRecord` in
  **Defragment** mode (the spec sketched `MergeContent`; the compiler generates the
  record-aware `MergeRecord` instead, since the whole flow already carries an explicit
  all-string record schema end-to-end — reassembling records, not opaque bytes), which
  reassembles the dataset in page order — a missing
  `fragment.index` cannot defragment, so the no-dropped-page property is enforced at
  reassembly, exactly as Hop's clone count enforces it. The graph is deliberately cyclic:
  an operator opening the canvas sees a loop, which is how a NiFi author would hand-build
  it — idiomatic, and the point of the generated-artifact legibility comparison ADR-0003
  exists to enable.

- **The legibility trade** the writeup will weigh: NiFi's shape *reads as iteration* — the
  cycle on the canvas is the pagination — but it costs the seed + RUN_ONCE mechanism (see
  below) and a cyclic graph that is harder to reason about as a byte-snapshot diff. Hop's
  shape is flatter and snapshot-friendlier but relies on `CloneRow` + a named `Sequence`,
  which do not announce themselves as "pagination" to a reader — the intent is inferred,
  not seen. ADR-0003's orthogonality hypothesis held: nothing about the *server* execution
  model forced either shape; the loop shape is an engine-idiom choice, not an
  execution-model consequence.

## Deviations from plan / spec

- **The M4 walkthrough does its own SFTP byte-compare, twice — inside the script, not in
  the gate shell.** The m2/m3 gates fetch-and-compare in a shell stage *after* the
  walkthrough. M4 cannot: the two engine deliveries write the **same** golden filenames to
  the same SFTP directory, so the second (hop) delivery overwrites the first (nifi) before
  any post-walkthrough shell stage could see it. The nifi byte-compare has to happen
  between the two runs, which only the walkthrough can interleave. `m4-walkthrough.mjs`
  therefore shells out to `sftp` itself (same non-interactive posture as the gates —
  committed host key, askpass, `-b` batch), once after each run. `e2e/m4-gates.sh` has no
  byte-compare stage of its own; the walkthrough owns it.

- **The engine flip carries the execution-model axis too.** The spec frames the flip as
  "update the Draft's `engine` field", but `nifi` is the `server` cell and `hop` is
  `batch` (only `nifi × server` deploys engine-side state — `CompilerBackedDeploymentCompiler`
  gates on both axes). So the walkthrough's two config variants flip `engine` **and**
  `executionModel` together (`nifi`+`server` ⇄ `hop`+`batch`); the logical DAG — nodes,
  edges, schedule — is byte-identical between them, which is the pluggability claim under
  test. A flip of `engine` alone would deploy the wrong cell.

## Gotchas (will bite again)

- **The parameter context is deleted in its own right — deleting the process group does
  not cascade to it.** NiFi does not protect the group→context binding, and dropping the
  group leaves the context orphaned. `RestNiFiDeployments.tearDown` deletes both by slug;
  the walkthrough's teardown assertion checks **both** `GET /nifi-api/flow/process-groups/root`
  (`.processGroupFlow.flow.processGroups[]`) **and** `GET /nifi-api/flow/parameter-contexts`
  (`.parameterContexts[]`) for a stray named `positions-feed`. Asserting only the group
  gone would miss a leaked context.

- **NiFi re-mints every live component id on upload; the compiler-minted ids survive as
  `versionedComponentId`.** The run driver resolves minted → live once at run start
  (`select(.component.versionedComponentId == $v)`); controller services arrive `DISABLED`
  and the deploy leaves them so (the credentials service refuses to enable against the
  context's empty sensitive values), so the driver enables them after late-binding real
  values. This is why deploy alone never produces a runnable group — the run protocol is
  load-bearing, not a formality. (Already in auto-memory; repeated here because the
  teardown assertion and the driver both depend on it.)

- **The NiFi verification uses `node:https` with `rejectUnauthorized:false`, not `fetch`.**
  NiFi's boot cert is self-signed and Node's built-in `fetch` has no per-request escape
  hatch for it (the M0 smoke posture). The walkthrough mirrors `e2e/smoke.mjs`'s
  `nifiRequest`; the control-plane calls stay on plain `fetch` (they're `http`). Do not
  reach for a global `NODE_TLS_REJECT_UNAUTHORIZED=0` — it would silently disable
  verification for every call in the process.

- **The gate needs `infra/.env` injected** (`node --env-file`, as the m0 smoke and m1
  walkthrough are run — unlike the m2 walkthrough's bare `node`): the walkthrough reads
  `NIFI_USER`/`NIFI_PASSWORD` for the
  teardown assertion and `SFTP_PASSWORD` for the byte-compare. The M0-chain gotchas still
  apply — NiFi's silent random credentials under a 12-char password, the driver image
  pre-pull (`badouralix/curl-jq@sha256:…`, M0 chain), the 240s poll ceiling.

## State at close

All M0 + M1 + M2 + M3 + M4 gates green via `e2e/m4-gates.sh` on a from-nothing world
(world left running; control plane stopped). The chain: `m3-gates.sh` rebuilds the world
from nothing and re-proves M0–M3 (its oracle-regen and byte-compare stages are the guard
that no fixture or delivered golden changed) → shared boot helper → world hygiene → the
M4 matrix walkthrough. The walkthrough proves the milestone: the **same** canonical
Positions Feed, deployed with `engine: nifi`, runs to SUCCEEDED and delivers the five
`positions_*_2026-07-17.csv` files **byte-identical to the M2 goldens** — identical bytes
from a second engine — then, flipped to `engine: hop` through the ordinary
Draft-update → redeploy lifecycle, tears down its NiFi process group and parameter context
(observed through NiFi's own API) and delivers the same five files byte-identical again.
Undeploy + delete leave the stage clean: no Dataflow, no Schedule firing, no process group.

No fixture or delivered-golden changes anywhere in the milestone — the deliverable goldens
already existed; that they match from NiFi is the point of the gate. `compiler-nifi`'s flow
definition and the nifi Kestra flow YAML are pinned by their own `./mvnw verify` snapshot
goldens (`NiFiArtifactGoldenTests`, `KestraFlowGoldenTests`), including the focused cyclic
pagination-loop subgraph case. Two engines now deliver the Positions Feed identically:
"pluggable" is a fact, and the M5 comparison study has its second subject.
