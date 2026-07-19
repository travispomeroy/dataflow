# M4.2 spike — NiFi data-plane fidelity (issue #39)

Throwaway, **not production code**. The findings are the deliverable and live as a
comment on issue #39; these scripts exist so every finding is re-runnable against the
live compose world (NiFi 2.10.0 at `https://localhost:8443`, WireMock, MinIO;
credentials from `infra/.env`). Node 24.18.0 (`nvm use 24.18.0`).

The spike hand-builds (via REST, piecewise) the full Positions Feed data plane on NiFi
and proves it delivers the five CSVs **byte-identical to the frozen M2 goldens**
(`e2e/golden/delivered/`), twice, plus a header-only file for an empty Asset Class.

| Script | Purpose |
|---|---|
| `00-probe-descriptors.mjs` | dumps live property descriptors for every processor/service type used (NiFi 2.x renamed properties; never guess) |
| `01-build.mjs` | builds the `m42-data-plane` process group: 3 pagination loops, defragment merges, ADR-0006 collapse, 2 JoinEnrichment stages, client filter, 5-way split (+ empty-class probe), CSV writer, PutS3Object → MinIO |
| `02-run.mjs <runTag>` | one run: drop queues → start PG (seeds DISABLED) → enable seeds → RUN_ONCE ×3 → drain poll → failure-funnel check → stop |
| `03-verify.sh <runTag>` | pulls the six staged objects, byte-compares the five real ones against the goldens, checks the probe is header+LF only |
| `04-export.mjs` | exports the flow-definition snapshot to `artifacts/m42-flow-definition.json` (the committed reference for compiler-nifi, M4.3) |
| `05-teardown.mjs` | M4.1 §3 deletion ordering + removes staged spike objects — world left as found |

Sequence for a fresh proof: `01 → 02 run1 → 03 run1 → 02 run2 → 03 run2 → 04 → 05`.
`schemas.mjs` holds the explicit all-string Avro schemas (the M2 verbatim-numerics
lesson); `lib.mjs` is the minimal REST client.

`artifacts/m42-flow-definition.json` is the exported reference flow — reference
material for M4.3's compiler goldens, explicitly not a production artifact. NiFi
omits the MinIO credential properties on export; `01-build.mjs` sets them from
`infra/.env`.

End state after `05-teardown.mjs`: NiFi has zero child process groups and zero
parameter contexts; no `m42-positions-feed/` prefix in the staging bucket.
