# Logical Dataflow Config, resolved snapshot per Deployment

The saved Dataflow Config contains only logical intent (Catalog references, transform
choices, schedule) — never endpoints, credentials, or merge mechanics. At Deploy time,
Compilation resolves the Catalog into an Execution Plan, which is stored as an immutable
snapshot on that Deployment version. This gives us both properties we need: users' saved
work survives infrastructure change (rotate a credential in the Catalog, redeploy — no
canvas surgery), and every past Deployment answers "what exactly did/will this do"
precisely, forever — the audit requirement of a financial feed.

## Considered Options

- **Resolve fresh every run** — simpler (no snapshot), but a Catalog edit silently changes
  a live feed's behavior with no deploy, no version, no record; past-run behavior is
  unreconstructable. Audit hole.
- **Bake physical details in at save time** — deterministic-feeling, but every saved config
  goes stale on any Catalog change until individually re-saved, and the user's artifact
  contains exactly the complexity this tool exists to hide.

## Consequences

- Engine compilers read Execution Plans only — they never touch the Catalog or logical
  config. That seam is what makes adding engine #3 tractable.
- "Physically complete" means complete **except secret material**: the plan (and every
  compiled artifact) carries credential *references*; values live only in the executing
  layer's secret store and are late-bound at run time. Snapshots, goldens, and flow
  definitions never contain secrets. Corollary: rotating a secret *value* changes a live
  feed with no redeploy — deliberately; only renaming the *reference* requires one.
- A live Deployment does not auto-heal on Catalog change: it keeps running its frozen plan
  (possibly failing) until redeployed. Deliberate — no silent behavior change to a live
  feed. A production version should flag stale Deployments; noted for productionization.
