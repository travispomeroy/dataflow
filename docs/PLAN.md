# Dataflow POC — Development Plan

A working proof-of-concept for **Dataflow**: an internally-hosted platform that lets
non-technical users in a financial firm build data feeds visually, while a control plane
compiles their intent into orchestrator and ETL-engine artifacts and the platform delivers
CSV files to providers over SFTP.

Vocabulary: [`CONTEXT.md`](../CONTEXT.md). Decisions: [`docs/adr/`](./adr/). This plan is
milestone-ordered for agentic development — every milestone ends with its verification
gates green.

---

## System overview

```
┌─────────────┐   Dataflow Config    ┌──────────────────────────┐
│  UI (React) │ ───────────────────► │  Control Plane (Spring)  │
│  React Flow │ ◄─────────────────── │  catalog │ dataflow      │
│  canvas     │   catalog, runs      │  compiler│ runners │ runs│
└─────────────┘                      └─────┬────────────────────┘
                                           │ compile → Execution Plan
                              ┌────────────┼──────────────┐
                              ▼            ▼              ▼
                        Kestra flow   NiFi flow def   Hop pipeline
                              │            │              │
                        ┌─────┴─────┐      └──────┬───────┘
                        │  Kestra   │── triggers ─┤ (via 4 Runners)
                        │ scheduler │             ▼
                        │ delivery  │◄─── MinIO staging ◄── engines write CSVs
                        └─────┬─────┘
                              ▼ SFTP (hidden upload → atomic rename)
                       Pomeroy Provider
```

### Components

| Component | Stack | Notes |
|---|---|---|
| UI | React + Vite + MUI + React Flow (latest; pin in M0), Nx workspace | 3 views: dataflow list, builder canvas, run history; live config JSON preview |
| Control plane | Java 25, Spring Boot 4.1.0, Spring Modulith, **Maven**, Postgres | Modules: `catalog`, `dataflow` (configs, draft→deploy lifecycle, versions), `compiler` (config→Execution Plan), `compiler-kestra`, `compiler-nifi`, `compiler-hop`, `runner` (4 strategies), `runs` (tracking) |
| Orchestrator | Kestra | One compiled flow per dataflow version; owns schedules, engine invocation, staging pickup, SFTP delivery |
| Engines | Apache NiFi, Apache Hop | Each in `server` and `batch` execution models → 4 runners, 2 compilers (ADR-0003) |
| Mock world | WireMock (3 paginated REST APIs), atmoz/sftp (Pomeroy Provider), MinIO (staging), Postgres | All docker-compose; deterministic committed fixtures, ≥3 pages per API |
| Event backbone (M8) | Kafka + CloudEvents | Run lifecycle events; replaces run-status polling |
| Fake legacy tool (M9) | Small Spring app ("Relic Reporting") | Own API + own delivery; walks the integration ladder |

### Key decisions (settled during grilling)

- **Engine & Execution Model**: per-dataflow operator-set fields, orthogonal axes (ADR-0003).
- **Delivery**: platform-owned via Kestra; engines stop at staging (ADR-0001). Hidden
  upload → rename all files atomically; never a visible partial feed.
- **Config**: logical only; Execution Plan snapshot frozen per Deployment (ADR-0002).
- **Lifecycle**: Draft → Deploy (versioned, linear); undeploy pauses schedule.
- **Concurrency**: per-dataflow limit 1; second trigger queues.
- **File naming**: catalog-owned token patterns (`positions_{assetClass}_{businessDate}.csv`);
  Destination owns base path; Business Date defaults to run date.
- **Five files rule**: Positions Feed splits by Asset Class (equity, fixed income, cash,
  derivatives, other); `advisor_group` carried into every file (fuels M6 Business Rules).
- **Pagination is mandatory**: all firm APIs paginate, so the mock APIs do too
  (page-number style: `?page=N&pageSize=M`, JSON envelope with `data` + `totalPages`).
  Pagination config (style, page size, termination) lives in the Catalog Source's
  physical definition; every engine compiler must generate page-loop extraction. Offset
  and cursor styles are catalog-modeled variants for later. The byte-golden e2e
  inherently proves pagination correctness — a dropped page changes the delivered files.
- **Client filter**: Client = investor; user multi-selects in UI from reference data.
- **Staging**: MinIO default; optional profile flips to real AWS S3 (same API).
- **Auth**: none (single implicit user). SSO/RBAC/approvals = productionization notes.
- **Persistence**: one Postgres container, two databases (`kestra`, `dataflow`).
- **Testing**: golden files at both layers — compiler snapshot tests (fast) + scripted
  compose-up e2e asserting the 5 CSVs on SFTP byte-match goldens (slow). Deterministic
  fixtures make byte-matching possible.
- **Integration ladder** for existing tools: Tier 3 Wrapped → Tier 2 Orchestrated →
  Tier 1 Native with Parity Proof (ADR-0004).

### Adopted defaults

Canvas enforces a connected Source→…→Destination graph before Deploy enables; Phase-1
graphs are linear (one source, one destination); undeploy removes the Kestra flow; NiFi
2.x HTTPS/single-user auth handled in infra; all container/library versions pinned during
M0 doc research (use context7 for current docs: React Flow/@xyflow/react, MUI, Nx, Vite,
Kestra, NiFi 2.x, Hop, Spring Boot 4.1, Spring Modulith).

### Repo layout

```
ui/              Nx workspace (React app + shared libs)
control-plane/   Maven, Spring Modulith service
infra/           docker-compose, DB init, WireMock fixtures, SFTP config, seeds
e2e/             golden files (compiled artifacts + delivered CSVs), verify scripts
docs/            this plan, ADRs, event schemas, comparison writeups, demo script
```

---

## Milestones

Business-phase mapping: the user-facing "phase 1 pass-through" = M2–M4; "phase 2 complex
ETL" = M6. Stretch = M8–M10. Engine landscape rationale: [`engine-survey.md`](./engine-survey.md).

### M0 — Scaffold: the world exists

- Repo layout above; Nx workspace with empty React app; Maven Spring Modulith skeleton.
- Doc research: pin current versions of every framework/container; record in
  `docs/versions.md`.
- Compose stack boots healthy: Postgres (2 DBs), Kestra, MinIO, WireMock, SFTP, NiFi, Hop.
- Fixture generator script (committed) → deterministic fixtures (committed): ~10 clients
  across ~4 advisor groups, 5 asset classes, a few hundred positions/orders, investors
  with `advisor_group`. WireMock serves `/positions`, `/orders`, `/investors` as
  **paginated** responses (generator emits per-page stub files; page size chosen so every
  API returns ≥3 pages).

**Exit gates**: `docker compose up` → all containers healthy; smoke script curls all three
mock APIs (verifying page envelopes and that walking all pages yields the full dataset),
MinIO, Kestra API, SFTP login; fixtures regenerate byte-identically.

### M1 — Domain core: config → Kestra-isms

- `catalog` module + seeds (Positions source with 3-API projection/merge definition
  **including per-API pagination config**; Pomeroy Provider destination with base path +
  credentials ref; File Definitions with token patterns). Execution Plans carry resolved
  pagination so engine compilers can generate page loops.
- `dataflow` module: Dataflow Config schema (Java + mirrored TS types), CRUD, Draft→Deploy
  versioned lifecycle, Execution Plan snapshot per Deployment.
- `compiler` module: config + catalog → Execution Plan.
- `compiler-kestra`: plan → per-dataflow Kestra flow YAML (schedule trigger, concurrency 1,
  engine-runner task placeholder, staging pull, hidden-upload+rename SFTP delivery).
- REST API: catalog read, dataflow CRUD, deploy, run-now, run history.
- `runs` module: poll Kestra executions API → run records (status, timing, delivered files).

**Exit gates**: golden snapshot tests green — Positions Feed config → expected Execution
Plan JSON and Kestra YAML; full API walkthrough scripted via curl.

### M2 — Hop batch path: first end-to-end feed

- `compiler-hop`: plan → Hop pipeline (`.hpl`): 3 paginated REST extractions (generated
  page loops) → merge on internal id → client filter → asset-class split → 5 CSVs →
  MinIO staging (`{dataflow}/{runId}/`).
- Runner `hop × batch`: Kestra docker task running `hop-run` with the compiled pipeline.
- Kestra delivery tasks live: pull staged files, SFTP hidden upload, atomic rename.
- Run tracking end-to-end; delivered file names + record counts recorded per run.

**Exit gates**: e2e script green — compose up → seed → deploy Positions Feed → run-now →
poll → 5 CSVs on SFTP byte-match golden files; run history API shows the run with files.

### M3 — UI: the non-technical experience

- Dataflow list (cards, status, last run), builder (React Flow canvas; palette from
  catalog API; property panel with client multi-select; Deploy + Run Now), run history
  view (status, timings, delivered files), collapsible live Dataflow Config JSON preview.
- Canvas validation: Deploy enabled only for a connected linear graph.

**Exit gates**: scripted browser test (Playwright) — build the Positions Feed on the
canvas from scratch → deploy → run → delivered files visible in run history; M2 e2e still
green.

### M4 — NiFi server path: the engine swap

- `compiler-nifi`: plan → NiFi flow definition (process group JSON), including the
  paginated-extraction loop pattern (InvokeHTTP + page cursor state) — expected to be the
  fiddliest generated piece; its cleanliness vs Hop's loop is comparison-writeup material.
- Runner `nifi × server`: deploy/replace process group via NiFi REST, parameter contexts
  for per-run values; per run: start group → poll until drained → stop.
- Engine field flip: same dataflow, `engine: nifi` → redeploy.

**Exit gates**: e2e matrix green for both engines — identical golden files from Hop and
NiFi; compiler snapshot goldens for NiFi flow definition; demo: flip engine in one API
call, rerun, same bytes delivered.

### M5 — Mirror execution models: the 2×2 study

- Runner `hop × server`: persistent Hop Server; register + trigger pipelines via its REST.
- Runner `nifi × batch`: Stateless execution (run-to-completion semantics).
- `docs/engine-comparison.md`: deploy latency, run latency, ops complexity, observability,
  failure behavior, generated-artifact legibility (esp. the pagination loops) — and the
  verdict on ADR-0003's orthogonality hypothesis (did any cell need a different artifact?).
- Optional baseline for the writeup: the pass-through feed implemented with Kestra tasks
  alone (no engine) — sharpens "what does an engine buy you?".

**Exit gates**: full 2×2 e2e matrix green against the same golden files; comparison
writeup complete.

### M6 — Business Rules: true ETL

- Catalog Business Rule pack **"Pomeroy Conventions"**: negate market value for Advisor
  Group XYZ; map internal asset-class codes → Pomeroy codes; rounding spec; suppress
  zero-quantity positions; derive concentration % (per-client aggregation joined back to
  rows — the deliberately hard one).
- Both compilers translate the pack; UI gains the single toggle node.
- New golden set for rules-on output.

**Exit gates**: rules-on and rules-off e2e green on both engines; snapshot goldens updated;
UI toggle drives the difference.

### M7 — Polish: the pitch

- `docs/DEMO.md` demo runbook (clean machine → full demo).
- AWS S3 staging profile (endpoint + creds swap; same code path).
- Productionization notes: SSO/RBAC, four-eyes approval workflow, destination
  entitlements, business-day/holiday calendars, stale-deployment detection on catalog
  change, Temporal for control-plane durability (deploy sagas, migration dual-runs — see
  engine-survey.md), Spark Declarative Pipelines as the engine-at-scale path.

**Exit gates**: demo runbook executed start-to-finish on a clean checkout; all test layers
green.

### M8 — Event backbone (stretch): Kafka + CloudEvents

- Kafka in compose; CloudEvents schemas published in `docs/events/`: `run.started`,
  `files.staged`, `run.delivered`, `run.failed`.
- Compiled Kestra flows emit events at those moments; `runs` module swaps polling for a
  consumer; UI gets live status via SSE.

**Exit gates**: polling code deleted; e2e passes with event-driven tracking; UI updates
live during a run without refresh; event schema docs published.

### M9 — Integrated Tools (stretch): the adoption ladder

- Spike → `docs/integration-contract.md`: the tiered contract (required endpoints per
  tier; Tier 2 events = the M8 CloudEvents contract, versioned).
- Build fake legacy tool **"Relic Reporting"**: small Spring app with its own nice API
  (list feeds, run job, job status) and its own SFTP delivery; ships a feed the old way.
- **Tier 3 — Wrapped**: Relic's feeds appear in the Dataflow UI (browse + trigger via its
  API).
- **Tier 2 — Orchestrated**: compiled Kestra flow triggers Relic jobs via API; status via
  its endpoints or emitted Platform Events; runs appear in Dataflow run history.
- **Tier 1 — Native + Parity Proof**: migrate one Relic feed to a native Dataflow Config;
  automated diff proves byte-identical delivery for the same Business Date.

**Exit gates**: all three tiers demoable in sequence; Parity Proof script green; contract
doc complete and versioned.

### M10 — Third engine experiment (stretch): DuckDB SQL codegen

Rationale in [`engine-survey.md`](./engine-survey.md). Deliberately cheap, two payoffs:
the artifact is plain SQL (readable by anyone — demo gold), and DuckDB is **batch-only**,
forcing the platform to model per-engine supported Execution Models (a fresh stress test
of ADR-0003).

- `compiler-duckdb`: Execution Plan → SQL script (merge, filter, business rules,
  asset-class split, CSV via `COPY`, S3 staging output).
- Extraction under paginated APIs: DuckDB's `read_json` cannot loop pages, so the runner
  splits — a generated fetch step walks the pages to staged JSON files; the SQL artifact
  owns transform + file production. (Document this E/T split as a survey finding.)
- Runner `duckdb × batch`: container running the fetch step + `duckdb -f plan.sql`.
- Config validation: engine capability matrix — `duckdb × server` rejected with a clear
  error at deploy time.

**Exit gates**: full e2e green with `engine: duckdb` against the same golden files
(rules-on and rules-off); capability-matrix validation test green; engine-survey updated
with findings.

---

## Verification strategy (every milestone)

1. **Compiler snapshot layer** (fast, no containers): given checked-in configs, generated
   Kestra YAML / NiFi flow definitions / Hop pipelines byte-match golden artifacts.
2. **E2E layer** (compose): scripted deploy → run → poll → SFTP contents byte-match golden
   CSVs. Deterministic fixtures make byte-equality valid.
3. A milestone is done only when both layers are green **and** all previous milestones'
   gates still pass.
