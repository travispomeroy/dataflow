# Shared violation examples

The keep-honest contract for deploy-time semantic validation (issue #30, M3.2): each
`*.violation.json` is a structurally valid but semantically invalid Dataflow Config,
annotated with the exact rule ids it must produce. Two suites assert against these
files — `SemanticValidationExampleContractTests` (Java, the authority) and
`semantic-validation.spec.ts` (the TS courtesy mirror in `ui/libs/dataflow-config`).
Change an example and both suites move together; that tripwire is the point (the M7
linearity relaxation will prove it).

File shape:

- `description` — why this config is invalid, in glossary language.
- `rules` — the **exact set of distinct rule ids** the validator must yield, sorted
  alphabetically. Set semantics, not multiset: per-node emission counts are an
  implementation detail; the ids are the contract.
- `config` — the Dataflow Config document, in the canonical form the control plane
  emits (explicit `null` for unset schedule/engine/executionModel).

Rule ids are defined by `SemanticViolation` (Java) and `SemanticRuleId` (TS):
`delivery-count`, `disconnected`, `cycle`, `non-linear`, `headless-path`,
`dangling-path`, `source-mid-path`, `delivery-mid-path`, `missing-operator-fields`.
Every id must be covered by at least one example; multi-violation examples prove all
rules run (the violations list is a complete fix-it list, not a first failure).
