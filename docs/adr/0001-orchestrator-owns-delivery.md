# Orchestrator owns Delivery, not the Engines

Both NiFi (PutSFTP) and Hop (SFTP transforms) can deliver files themselves, but we decided
Engines stop at Staging (MinIO) and the Orchestrator (Kestra) performs all SFTP Delivery.
Delivery is a platform concern: one code path regardless of Engine × Execution Model, one
place for per-file retries and hidden-upload-then-rename atomicity, and one audit point for
"what did we ship to whom" — which a financial tool cannot leave scattered across engines.
It also keeps the engine comparison honest: engines are compared on transform fidelity, not
delivery quirks.

## Consequences

- Every Engine must write to Staging; a Staging path convention (`{dataflow}/{runId}/`) is
  part of the compiler contract.
- Integrated Tools (see ADR-0004) at Tiers 3 and 2 keep their own delivery — a documented,
  deliberate exception. Reaching Tier 1 means adopting platform Delivery.
