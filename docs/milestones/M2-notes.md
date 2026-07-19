# M2 — Hop batch path: as-built notes

Spec: issue #19; tickets #20–#27. Everything below is what deviated from the plan, was
decided mid-flight, or will bite the next person if forgotten. What went exactly to plan
is not repeated here — see `docs/PLAN.md` M2, the spec, and the M2 commit bodies (each
records its ticket's own load-bearing decisions).

## The pagination finding (recorded per spec)

Hop 2.18.1 has **no native REST pagination** — the REST-connection pagination feature
merged to Hop main on 2026-06-15 and is unreleased as of this milestone. The compiler
therefore generates an explicit declarative page loop per API (probe page 1 → JsonInput
`totalPages` from the envelope → CloneRow one row per page → per-branch named Sequence
counter → URL-from-field REST), with the Catalog's `totalPages` contract as termination.
A dropped page is impossible by construction and visible in the bytes. When the next Hop
release ships native pagination this is comparison-writeup material for the M4/M5 engine
study — the pin is **not** to be bumped for it.

## Spike outcomes (M2.3, issue #22 — full write-up on the ticket)

- **Bare `hop-run` needs no scaffold**: the pinned image ships a mandatory `default`
  project whose `local` run configuration is the default selection, so a lone `.hpl`
  runs as-is.
- **`minio://` is metadata, not a built-in scheme**: the MinIO VFS plugin registers one
  scheme per `MinioConnectionDefinition` metadata object. Mechanism picked: a metadata
  JSON (fields are `${HOP_MINIO_*}` expressions) copied into the active project's
  metadata folder, credentials late-bound as JVM system properties via the `HOP_OPTIONS`
  env var (ADR-0002: artifacts carry references, never values). Plain container env vars
  are *not* Hop variables; `hop-conf` has no MinIO options; `--metadata-export` is a trap
  (feeds pipeline metadata but not VFS scheme registration — `minio://` paths silently
  become local files).
- **Direct `TextFileOutput` → `minio://` is broken in Hop 2.18.1**: 0-byte objects for
  buffer-sized files, truncated beyond that, exit code still 0 (close-path IOExceptions
  are swallowed). Unchanged on Hop main as of the spike. Hence the artifact **pair**:
  the `.hwf` workflow wraps the `.hpl` — produce CSVs locally, stage to
  `minio://staging/{dataflow}/{runId}/` with a Copy Files action (single clean VFS
  stream copy, proven byte-identical).
- **Verbatim numerics hold** with JsonInput fields typed String end-to-end (`0`, `1381`,
  `3750.955` byte-match the fixtures); no raw-text fallback needed.

## Deviations from plan / spec

- **The engine artifact is a pair, not a single pipeline**: the plan had the pipeline
  writing the five CSVs directly to Staging; the VFS truncation bug (above) forced the
  `.hpl` + `.hwf` shape. Both are embedded in the compiled flow via `inputFiles` (plus
  the MinIO connection metadata JSON as a third input file), so the flow pushed to
  Kestra remains the single self-contained Deployment artifact.
- **The staging bucket is provisioned by compose**, not created at run time: a directory
  created under `/data` before the minio server starts is adopted as a real bucket on
  the pinned release. The spike had created it by hand; nothing in the repo provisioned
  it until M2.4.
- **The M2.8 walkthrough is its own small script**: `e2e/m2-walkthrough.mjs` deploys a
  fresh canonical Positions Feed, runs it with the pinned Business Date, and pins the
  run record — then **undeploys** (not an assertion; without it the daily Schedule keeps
  firing runs in the left-running world). The M1 walkthrough inside the chain already
  proves the full lifecycle — its honest-FAILED assertion was flipped to SUCCEEDED in
  M2.5, as the M1 notes anticipated, so M2.8 had nothing left to flip.

## Mid-flight decisions

- **Kestra secrets are committed twice in `infra/.env`**: Kestra OSS reads secrets as
  base64 `SECRET_*` env vars and compose cannot encode, so each value is committed plain
  + `*_B64`; `m0-gates.sh` asserts the pairs cannot drift.
- **Sibling-container plumbing**: the Kestra compose service gained the Docker socket
  and a `/tmp/kestra-wd` working-dir volume mounted at the *same path* on both sides
  (sibling bind mounts resolve through the host daemon), with
  `kestra.tasks.tmp-dir.path` to match.
- **The count task feeds on a jq-built name→uri map**, not the staging pull's `objects`
  array — Kestra 1.3.28 rejects the array itself as a task input. Capture of
  `deliveredFiles` is part of the poller's transition into SUCCEEDED, so a failed
  read-back retries on the next poll; `delivered_files` was promoted text → jsonb (V4,
  `USING` cast) as the M1 notes planned.

## Gotchas (will bite again)

- **Do not "simplify" the `.hwf` away**: direct VFS output from the pipeline is the
  silent-truncation bug. The workflow wrapper *is* the fix.
- **Hop element shapes come from what 2.18.1 itself serializes** (bundled samples + the
  spike), never invented. Two found-live traps encoded in the compiler: an *unnamed*
  Sequence counter is pipeline-global (concurrent branches interleave page numbers), and
  a `FilterRows` without explicit true/false targets passes every row through untouched.
- **The control-plane boot block is copied, not shared**: `m2-gates.sh` Stage 3 mirrors
  `m1-gates.sh` Stage 3 (squatter check, jar glob, EXIT trap, readiness poll) because
  the M1 gate stops its instance on exit and gate scripts stay self-contained. A
  startup-seam change must land in both.
- **The oracle-regen gate deletes `e2e/golden/delivered/` before regenerating** (mirror
  of the fixture-regen gate): if it fails, restore with
  `git checkout -- e2e/golden/delivered`.
- **Fixture freeze is in force** since M2.2 (`af1c704`): any fixture change from here on
  is a golden-invalidation event requiring explicit justification.

## State at close

All M0 + M1 + M2 gates green via `e2e/m2-gates.sh` on a from-nothing world (world left
running; control plane stopped). The chain proves: oracle regen byte-stable → canonical
Positions Feed deployed fresh → run-now with `businessDate=2026-07-17` → SUCCEEDED →
the five `positions_*_2026-07-17.csv` files fetched back off Pomeroy Provider's SFTP
byte-identical to the independent oracle's goldens → run history returns exactly the
golden names and record counts. Engine and oracle agree; a contract change must be made
twice to go green. Fixtures untouched since the freeze.
