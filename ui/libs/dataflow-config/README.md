# dataflow-config

Hand-written TS mirror of the Dataflow Config schema (M1.3) and of the deploy-time
semantic rule set (M3.2). Source of truth for both: the control plane — the Java
records in the `dataflow` module for the schema, `SemanticValidator` in the `compiler`
module for the rules. Both sides are held honest by the shared canonical examples in
`e2e/canonical/` — the Java side round-trips them (`DataflowConfigContractTests`),
this side type-checks them (`checks/canonical-examples.ts`, built by
`tsconfig.check.json` alongside the lib).

`semantic-validation.ts` is the runtime half: `validate(config) → violations[]`, the
canvas's synchronous courtesy pre-check, keyed by the same rule ids as the Java
validator with a user-facing glossary message per rule. Its keep-honest contract is
the annotated violation examples in `e2e/canonical/violations/` — the vitest suite
(`semantic-validation.spec.ts`, the `test` target) and the Java
`SemanticValidationExampleContractTests` both assert exact-id matches against them, so
neither rule set can drift alone. The control plane remains the only authority; the
mirror is a courtesy preview. No codegen either way.

## Why package.json carries an `nx.targets` inputs override

The canonical examples live at the repo root, *outside* the Nx workspace (`ui/`), and
`tsconfig.check.json` must list them (`tsc` composite projects require every input,
TS6307). Nx derives task-hash filesets from tsconfig includes and rejects any path that
escapes the workspace — so the override replaces the derived inputs and re-adds the
external JSONs as a `runtime` input (`cat ../e2e/canonical/*.json`, run from the
workspace root). Without it, editing a canonical example would not bust the Nx cache
and a stale green typecheck could mask contract drift.
