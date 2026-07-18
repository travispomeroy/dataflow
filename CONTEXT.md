# Dataflow

An internal financial data-movement platform: non-technical users compose feeds visually, a
control plane compiles their intent into orchestrator and engine artifacts, pluggable ETL
engines do the work, and the platform delivers the files.

## Language

### Core

**Dataflow**:
A user-authored definition of data movement: one Source, zero or more Transforms, and one
Delivery to a Destination, on a Schedule. The central aggregate of the system.
_Avoid_: pipeline, job, workflow

**Dataflow Config**:
The persisted, purely *logical* representation of a Dataflow — Catalog references, transform
choices, schedule, and operator-set Engine/Execution Model fields. Never contains physical
details (endpoints, credentials, merge mechanics).
_Avoid_: flow definition (that's a compiled NiFi artifact)

**Transform**:
A user-visible operation applied to a Source's data within a Dataflow (e.g., "filter by
Clients", "apply a Business Rule"). User-facing vocabulary stays simple even when the
underlying logic is not.

**Business Rule**:
An admin-authored, named piece of complex transform logic stored in the Catalog. Users add
it to a Dataflow as a single simple node — they choose *whether* it applies, never *how* it
works.

**Client**:
The account holder whose positions and orders flow through a Dataflow. Each Client belongs
to exactly one Advisor Group.
_Avoid_: investor (upstream API name only), account, customer

**Advisor Group**:
The advisory organization a Client belongs to; carried through the merge into every output
file. The business key that drives Business Rules.

**Asset Class**:
The classification of a position (equity, fixed income, cash, derivatives, other) that
drives the Positions Feed's one-file-per-class split.

**Business Date**:
The as-of date a Run's data represents, stamped into file names and delivery paths.
Defaults to the run date but is conceptually distinct from it.
_Avoid_: run date (when the Run happened, not what the data is as-of)

**Schedule**:
When a Dataflow runs automatically. A first-class concept (not a bare cron string) — real
feeds have business-day and holiday calendars. Any deployed Dataflow can also be run
manually on demand.

### Catalog

**Catalog**:
The curated registry of Sources, Destinations, and Business Rules available to users.
Admin-authored, read-only in the UI. The boundary where hidden complexity lives.

**Source**:
A Catalog entry representing a *logical* dataset a user can start a Dataflow from (e.g.,
"Positions"). May physically be a projection over multiple upstream systems; that
composition is invisible to the user.

**Destination**:
A Catalog entry representing a place files can be delivered (e.g., "Pomeroy Provider").
Owns connection details, credentials, and the base delivery path. Users pick it by name.

**File Definition**:
The specification of one output file a Dataflow produces: a token-based name pattern
(e.g., `positions_{assetClass}_{businessDate}.csv`), projection, and rules. One Dataflow
may produce many files from one Source.

### Compilation & execution

**Compilation**:
Translating a Dataflow Config into everything needed to execute it: resolving Catalog
references into an Execution Plan, then generating engine and orchestrator artifacts.

**Execution Plan**:
The fully *resolved* form of a Dataflow Config — catalog references expanded into physical
extraction, merge, transform, and file-production steps. Engine-agnostic but physically
complete.

**Deployment**:
The act of publishing a compiled Dataflow version so it can run. Each Deployment stores its
Execution Plan as an immutable snapshot — the audit record of exactly what it will do.

**Run**:
A single execution of a deployed Dataflow, with discrete start, end, and success/failure.
Runs of the same Dataflow never overlap; a second trigger queues.
_Avoid_: execution (Kestra's term; use only when speaking Kestra)

**Engine**:
The pluggable ETL technology performing extract/transform work (POC: Apache NiFi, Apache
Hop). Operator-chosen per Dataflow; invisible to end users.

**Execution Model**:
*How* an Engine executes a Run: `server` (long-running engine, runs triggered against it)
or `batch` (ephemeral run-to-completion). Orthogonal to Engine; operator-set.

**Runner**:
The strategy that deploys and triggers a compiled artifact for one Engine × Execution
Model combination.

**Orchestrator**:
The scheduling/coordination layer (POC: Kestra). Owns triggering, sequencing, Delivery,
and run status.

**Staging**:
The intermediate location where an Engine places produced files for the Orchestrator to
pick up — the handoff point between Engine and Delivery.

### Delivery

**Delivery**:
The act of shipping a Run's output files to a Destination. Owned by the platform, not the
Engine; identical behavior regardless of Engine. Files upload under hidden names and are
renamed to final names only when all are complete, so a partial feed is never visible.

### Events & integration

**Platform Event**:
A CloudEvent the platform emits at run lifecycle moments (run started, files staged,
delivered, failed). The platform's public contract — consumed by the control plane, the
UI, and Integrated Tools.

**Integrated Tool**:
An existing, independently-built system (e.g., a legacy reporting tool) plugged into
Dataflow at some Integration Tier. Distinct from an Engine: an Engine is platform-managed
execution technology; an Integrated Tool is a peer system with its own life.

**Integration Tier**:
The adoption ladder for Integrated Tools. **Tier 3 — Wrapped**: the tool's feeds appear in
the Dataflow UI via its API; it keeps its own scheduling, transforms, and delivery.
**Tier 2 — Orchestrated**: Dataflow owns scheduling and triggers the tool's jobs via its
API; the tool still transforms and delivers. **Tier 1 — Native**: the feed is migrated
into a real Dataflow Config on platform Engines with platform Delivery. Migration is
feed-by-feed; one tool can sit at different tiers for different feeds.

**Parity Proof**:
An automated comparison showing a migrated (Tier 1) feed's delivered output matches the
legacy tool's output for the same Business Date.

### POC scenario

**Positions Feed**:
The flagship Dataflow: the "Positions" Source (physically: positions, orders, and
investors REST APIs merged on internal id), filtered by Clients, producing five files —
one per Asset Class, each carrying Advisor Group — delivered to "Pomeroy Provider".
