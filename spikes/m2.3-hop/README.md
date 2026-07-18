# M2.3 Hop spike — throwaway, NOT production code

Reference artifacts for issue #22: a hand-written minimal Hop pipeline run via
`hop-run` inside the pinned `apache/hop:2.18.1` container against the live compose
world. The findings (the actual deliverable) are recorded as a comment on issue #22;
this directory exists so the evidence is reproducible. Nothing here feeds the M2.4
compiler directly.

## Contents

| File | What it is |
|---|---|
| `spike-positions.hpl` | RowGenerator → REST (`/positions` page 1) → JsonInput (all fields **String**) → TextFileOutput. Parameters `API_BASE`, `OUTPUT_BASE`. |
| `spike-stage.hwf` | Workflow: Copy Files action staging the produced CSV to `minio://staging/...` — the **working** staging write. |
| `metadata/MinioConnectionDefinition/minio.json` | MinIO connection metadata; the object **name** (`minio`) registers the `minio://` VFS scheme. Fields are `${HOP_MINIO_*}` expressions resolved from system properties. |
| `metadata-export.json` | Evidence of a trap: `hop-run --metadata-export` feeds the pipeline's metadata but **not** VFS scheme registration — `minio://` paths silently become local paths. Kept as the negative finding; do not use. |
| `run-local.sh` | Q1/Q4/Q3: bare-container `hop-run` against WireMock, CSV to `./out/`. |
| `run-minio.sh` | Q2: end-to-end staging proof (bucket ensure → local CSV → VFS copy → byte-compare). |
| `demo-broken-direct-write.sh` | Reproducer for the direct-write defect: exits 0 while the 0-byte-object bug reproduces, exits 1 the day a Hop bump fixes it. |
| `verify-literals.mjs` | Q3: byte-compares the CSV against the raw fixture JSON literal text (no number round-trip in the checker itself). |

## Running

Compose world must be up (`docker compose --project-directory infra up --wait`).

```sh
./run-local.sh                 # bare hop-run, CSV lands in ./out/
node verify-literals.mjs       # literal-preservation proof over ./out/
./run-minio.sh                 # staging proof via minio:// VFS
./demo-broken-direct-write.sh  # the negative finding, reproduced live
```

## Headline findings (details on issue #22)

1. **Bare `hop-run` needs no scaffold**: the image ships a `default` project with a
   `local` run configuration; `./hop-run.sh --file=<x.hpl> --runconfig=local` just works.
2. **MinIO VFS**: scheme registration requires a `MinioConnectionDefinition` metadata
   object in the *project* metadata; credentials late-bind via `${HOP_MINIO_*}` from
   JVM system properties (`HOP_OPTIONS` env var). Plain env vars are not Hop variables.
3. **Direct `TextFileOutput` → `minio://` is broken in Hop 2.18.1**: 0-byte object for
   small files, truncated object for larger ones, and the pipeline still reports
   success (close-path IOExceptions are swallowed with `printStackTrace`). Unfixed on
   Hop `main` as of 2026-07-18. Stage via a Copy Files workflow action (single clean
   VFS stream copy) — proven byte-identical — or have the orchestrator own the upload.
4. **Verbatim literals**: JsonInput with String-typed fields preserves upstream numeric
   literals byte-for-byte (`0`, `1381`, `3750.955`); no raw-text fallback needed. All
   fixture values are far below the `Double`→scientific-notation threshold (max
   585448.35 vs 1e7).
