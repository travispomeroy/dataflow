# Dataflow Config models a DAG

The original plan constrained Phase-1 user graphs to a linear source → transforms →
destination chain. We decided instead that DAG support is a product conviction — real
feeds branch and converge, and every engine we would ever plug in executes DAGs natively —
so the Dataflow Config schema is nodes + edges from M1. What is *compilable* is a
deploy-time validation rule, not a schema property: saves accept any structurally
well-formed graph, deploys reject shapes the platform cannot yet run (linear-only until
M7, which proves the diamond-shaped Client Exposure Feed on both engines).

## Considered Options

- **Linear schema, generalize later** — makes illegal states unrepresentable and is all
  the POC's flagship feed needs, but the generalization would land on the central
  aggregate after five milestones of consumers (compilers, validators, mirrored TS types,
  canvas) had been built against the linear shape — a reshaping of everything at once.
- **DAG schema from day one (chosen)** — consumers handle graph topology from the start;
  M7 becomes a *relaxation* of one validation rule rather than a schema migration.

## Consequences

- Semantic graph validation (connected, acyclic, exactly one Delivery, compilable-now)
  lives in exactly one place: the control plane at Deploy. The canvas's checks are a
  courtesy preview, never a second authority.
- Fan-out is broadcast: every downstream branch sees all rows, never a partition.
- Convergence is a user-visible **Join** Transform; **Aggregate** (group-by + measures)
  arrives with it in M7. The hidden merge inside a Catalog Source is a different concept
  and keeps its own name.
- The Positions Feed stays a straight line — linearity is now a fact about that feed,
  not about the platform.
