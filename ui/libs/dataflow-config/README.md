# dataflow-config

Hand-written TS mirror of the Dataflow Config schema (M1.3). Source of truth: the Java
records in the control plane's `dataflow` module. Both sides are held honest by the
shared canonical examples in `e2e/canonical/` — the Java side round-trips them
(`DataflowConfigContractTests`), this side type-checks them
(`checks/canonical-examples.ts`, built by `tsconfig.check.json` alongside the lib).
Types only: no runtime validation, no codegen.

## Why package.json carries an `nx.targets` inputs override

The canonical examples live at the repo root, *outside* the Nx workspace (`ui/`), and
`tsconfig.check.json` must list them (`tsc` composite projects require every input,
TS6307). Nx derives task-hash filesets from tsconfig includes and rejects any path that
escapes the workspace — so the override replaces the derived inputs and re-adds the
external JSONs as a `runtime` input (`cat ../e2e/canonical/*.json`, run from the
workspace root). Without it, editing a canonical example would not bust the Nx cache
and a stale green typecheck could mask contract drift.
