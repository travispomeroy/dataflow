# ETL Engine Survey

An evaluation of ETL engines and adjacent tech that could serve as Dataflow **Engines**
(see `CONTEXT.md`), beyond the two chosen for the POC (Apache NiFi, Apache Hop). Facts
about project status, licensing, and versions were verified against primary sources in
**July 2026**; this field moves — re-verify before acting on this document later.

## The yardstick

An Engine must survive this sentence: *"The compiler translates an Execution Plan into a
**declarative artifact** the engine executes headlessly, deployed and triggered by a
**Runner** under Kestra."* Five tests:

1. **Compilable artifact** — a well-defined generatable format (YAML/JSON/XML/SQL).
   Code-first tools fail or need codegen.
2. **Headless batch-ish execution** — a run starts, finishes, reports success/failure.
3. **Dataset operations** — multi-source join, filter, aggregate, split, CSV out — *and
   paginated REST extraction* (all firm APIs paginate; non-negotiable).
4. **Self-hostable, clean license** — internal hosting at a financial firm. The
   2022–2025 license-change era makes this a real filter.
5. **Ops weight** — cost of the `server` model staying alive; cold-start cost of `batch`.

## Chosen engines (POC scope, unchanged)

- **Apache NiFi** — flow-based, visual canvas, streaming worldview. Server model native;
  batch via Stateless execution. Paginated REST = InvokeHTTP loop patterns (known-fiddly;
  part of what the POC measures).
- **Apache Hop** — dataset-batch worldview, visual designer, the maintained successor of
  Kettle/PDI. Batch via `hop-run`; server via Hop Server. Paginated REST = REST step in a
  loop construct.

Together they span the two dominant paradigms (flow-streaming vs dataset-batch), both have
canvases coworkers can peek behind, and both pass all five tests.

## Serious candidates

### DuckDB (SQL codegen) — ⭐ recommended third-engine experiment (M11)

- **Artifact**: a generated SQL script — readable by anyone who knows SQL, which makes the
  artifact itself demo material ("the engine can even be plain SQL").
- **Dataset ops**: superb — joins/aggregates/splits are its native tongue; CSV via `COPY`.
- **Extraction**: v1.5.x reads JSON REST endpoints over HTTPS (`httpfs` + `json`
  extensions) with auth-header support via secrets — but **GET-only and no native
  pagination looping**. Under the firm's paginated-API constraint, pure-SQL extraction
  isn't viable. M11 therefore splits the runner: a generated fetch step pulls pages to
  staged JSON files, and the SQL artifact owns transform + file production. (An all-SQL
  `UNION` over page URLs is possible only when page counts are known — a fragile hack;
  rejected.)
- **The architectural payoff**: DuckDB has **no server model** — a batch-only engine.
  Supporting it forces the config schema to express per-engine supported Execution
  Models, stress-testing ADR-0003 from a new angle.
- **License/status**: MIT, DuckDB Foundation, very active (1.5.4 Jun 2026; LTS line
  exists).

### Apache Camel — reserved for M10 (integration adapters), not an engine slot

- **Artifact**: YAML DSL is first-class; `camel-jbang` runs YAML routes directly
  (supported, documented path); **Karavan** visual designer is alive and tracks Camel
  releases. Camel 4.20 supports JDK 25 — matches our stack. Compilability: verified pass.
- **Weakness**: test 3. Camel is message/EIP-oriented, not dataset-oriented — a three-way
  join means enricher chains with custom aggregation strategies; the generated artifact
  gets ugly, and the artifact is the demo.
- **Superpower**: protocol adaptation. Legacy tools with old API layers (auth quirks,
  SOAP corners, retries) are exactly Camel's home turf. If M10's real-world sequel meets a
  tool too weird to integrate directly, a thin Camel layer implementing the Tier 2/3
  adapters (tool API ⇄ Platform Events) is the natural move.
- **Status**: very active; two LTS releases/year (current LTS 4.18).

### Apache Spark (+ Declarative Pipelines) — the engine-at-scale path, not POC scope

- **Field shift (Dec 2025)**: Spark 4.1 shipped **Spark Declarative Pipelines** —
  Databricks donated Delta Live Tables to Apache — a vendor-neutral *declarative* pipeline
  spec. That flips Spark's historical weakness (no compilable artifact) to a pass.
- **Verdict**: massive ops weight and cold-starts make it wrong for feed-sized nightly
  CSVs, but if "make everything Dataflow-native" ever includes feeds too big for
  single-node engines, Execution Plan → SDP preserves the compile model. Record the path;
  build nothing now.

### Redpanda Connect (ex-Benthos) / Bento — attractive, but weak at our core demo

- **Artifact**: a single beautiful YAML; single Go binary; trivially containerized.
- **Licensing resolved** (was the concern): Redpanda Connect core is MIT (enterprise
  connectors paid); the pre-acquisition MIT fork **Bento** is actively maintained under
  Confluent's WarpStream Labs. Both healthy as of July 2026.
- **Weakness**: it's a stream processor — mapping/filtering (Bloblang) shine, but
  multi-source dataset *joins* are not its idiom, and joins are the heart of the
  Positions Feed. Honorable mention; revisit for join-light feeds.

### Apache SeaTunnel — honorable mention

ASF top-level, config-driven source/transform/sink engine, multi-engine runtime
(Zeta/Spark/Flink), active. Declarative config passes test 1, but its transform
vocabulary is thinner than NiFi/Hop and its center of gravity is *sync/movement*, not
transformation. Watch; don't build.

## Adjacent tech that is not an Engine

### Temporal.io — durable execution, wrong axis

No declarative artifact — Temporal workflows are code; using it as an engine means either
codegen (fragile) or building a generic plan-interpreting worker (i.e., building our own
ETL engine — explicitly out of scope). Where it *would* earn a place: control-plane
durability — the deploy saga, Tier-1 migration dual-runs, anything that must survive a
restart mid-operation. Recorded in productionization notes (M8). Not a Kestra
replacement here: Kestra's declarative YAML is precisely what makes "compile the
Kestra-isms" a clean generation step; Temporal would turn that into code deployment.

### EL(T) platforms — wrong shape

- **Airbyte** — ELv2 (source-available; fine for internal use), active. E and L only;
  transforms delegated to dbt; it's a rival *platform* with its own UI/orchestration, not
  an embeddable engine.
- **Meltano / Singer** — alive (v4.2.x mid-2026) but the company pivoted to an AI product
  (Arch); stewardship is a watch item. Same shape problem as Airbyte, lighter.
- **dlt** — Apache 2.0, very active, genuinely good — and code-first (Python library):
  fails the compilable-artifact test without codegen.
- **dbt** — compilable SQL models, transform-only; assumes data already landed. Folds
  into the DuckDB option (which subsumes the useful part for our sizes).

### Not suitable

- **Apache Flink** — streaming-first, heavy ops; wrong paradigm for nightly batch feeds.
- **Embulk** — officially in maintenance mode (Nov 2025 announcement); avoid for new
  builds.

## Dead / avoid — recorded so nobody re-proposes them

| Tool | Status |
|---|---|
| **Talend Open Studio** | Discontinued by Qlik, EOL Jan 2024. Community fork **Talaxie** exists (Apache 2.0, small team) — viability unproven. |
| **Kettle / Pentaho PDI** | Effectively succeeded by its fork — that's what Apache Hop *is*. Use Hop. |
| **Spring Cloud Data Flow** | Open-source maintenance ended Apr 2025 (Broadcom); commercial-only; repo attic'd. (**Spring Batch** itself remains healthy Apache-2.0 OSS.) |

## Verdict

| Candidate | Verdict |
|---|---|
| NiFi, Hop | POC engines (M2–M7) — unchanged |
| DuckDB SQL codegen | Third-engine experiment — **M11** |
| Camel | M10 toolbox: legacy-tool adapters |
| Spark + Declarative Pipelines | The documented at-scale path; no POC work |
| Redpanda Connect / Bento, SeaTunnel | Honorable mentions; join-weak / sync-centric |
| Temporal | Control-plane durability note (M8); not an engine |
| Airbyte, Meltano, dlt, dbt | Wrong shape (platform / code-first / transform-only) |
| Flink, Embulk, Talend, Kettle, SCDF | No |

One baseline worth including in the M5 comparison writeup: **Kestra-as-engine** — the
pass-through feed implemented purely with Kestra's own tasks, no engine at all. Not a real
Engine (no artifact separation; blurs ADR-0001), but it sharpens the study's central
question: *what does an engine buy you over the orchestrator doing the work?*
