# Engine and Execution Model are orthogonal per-dataflow axes

A Dataflow Config carries two independent operator-set fields — `engine: nifi | hop` (what
technology transforms the data) and `executionModel: server | batch` (how a run comes into
existence) — rather than one fused enum (`nifi-server`, `hop-batch`, ...). The engine axis
determines the compiled artifact (NiFi flow definition / Hop pipeline); the execution-model
axis determines the Runner (deploy-and-trigger strategy). The 2×2 therefore costs 2
compilers + 4 runners, not 4 monolithic integrations, and the POC's engine/model comparison
becomes expressible as four config variations whose e2e outputs must be identical.

## Considered Options

- **Fused mode enum** — every engine × model combination becomes a bespoke integration;
  nothing enforces that `nifi-server` and `nifi-stateless` share an artifact, so the
  comparison study degrades to apples-vs-oranges.
- **Global engine setting** — kills per-dataflow swap, side-by-side demos, and the e2e
  matrix.

## Consequences

- We accepted doubling the runner surface (4 instead of 2) to buy the comparison study.
- The orthogonality is a hypothesis, deliberately stress-tested: if any cell needs a
  *different artifact* for server vs batch, the assumption is falsified and the engine
  comparison writeup must document where it bends.
