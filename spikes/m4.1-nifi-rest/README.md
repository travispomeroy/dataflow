# M4.1 spike — NiFi REST lifecycle mechanics (issue #38)

Throwaway, **not production code**. The findings are the deliverable and live as a
comment on issue #38; these scripts exist so every finding is re-runnable against
the live compose NiFi 2.10.0 (`https://localhost:8443`, credentials from
`infra/.env`).

Run in order; state (component ids) passes between scripts via
`$TMPDIR/m4.1-*.json` files.

| Script | Spike question |
|---|---|
| `01-token.sh` | Q1 — token mint, JWT lifetime claims, reuse |
| `02-probe-flow.sh` | builds the probe PG (seed → stopped sink, enabled CSVReader) |
| `03-run-once.sh` | Q5 — RUN_ONCE on a stopped processor: exactly one flowfile |
| `04-drain.sh` | Q6 — PG-wide + per-connection queue depth, drop-all-queues request |
| `05-download.sh` | Q2a — flow-definition snapshot shape, identifier fields |
| `06-upload.sh` | Q2b — minted-identifier upload; the preservation verdict |
| `06b-upload-again.sh` | Q2c — same artifact twice: live ids are fresh per upload |
| `07-parameter-context.sh` | Q4 — create/bind/async-update, sensitive write-only |
| `08-replace-blockers.sh` | Q3 — the three delete-blocking 409s and cleanup ordering |
| `09-teardown.sh` | Q7 — full deletion, verified absent |
| `10-seed-gating.sh` | bonus — DISABLED seed survives PG start; enable → RUN_ONCE |

`artifacts/` holds the flow-definition snapshot NiFi exported for the probe PG
(`m4.1-download.json`) and the rewritten upload document with compiler-style
minted identifiers and instanceIdentifiers stripped (`m4.1-minted.json`).

End state after the full sequence: NiFi has zero child process groups and zero
parameter contexts — the world is left as found.
