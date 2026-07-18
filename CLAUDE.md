# Dataflow POC

Internal financial data-movement platform: non-technical users build feeds on a canvas; a
control plane compiles Dataflow Configs into Kestra + ETL-engine artifacts; the platform
delivers CSVs over SFTP. Full pitch and architecture: `docs/PLAN.md`.

## Read before working (in order)

1. `CONTEXT.md` — the ubiquitous language. Use these exact terms in code, config, UI copy,
   and docs. Respect the `_Avoid_` lists.
2. `docs/PLAN.md` — system overview, settled decisions, milestones M0–M11 with exit gates.
3. `docs/adr/` — five binding decisions. Do not re-litigate them; propose a superseding
   ADR if one must change.
4. `docs/milestones/` — as-built notes from completed milestones (deviations from plan,
   gotchas, mid-flight decisions). Read the notes for all completed milestones before
   starting a new one.
5. `docs/engine-survey.md` — why NiFi/Hop/DuckDB and not the alternatives.

## Current status

- **Completed**: planning (glossary, plan, ADRs, engine survey); M0 — Scaffold;
  M1 — Domain core
- **Next**: M2 — Hop batch path: first end-to-end feed
- Update this section at every milestone close-out.

## Standing rules

- A milestone is DONE only when its exit gates pass **and** all previous milestones' gates
  still pass. Gates are scripted (`e2e/`) — run them, don't trust memory.
- Milestone close-out ritual: gates green → write `docs/milestones/M<n>-notes.md` →
  update Current status above → commit.
- `CONTEXT.md` is a glossary only — never put implementation details in it.
- Deterministic everything: fixtures, golden files, generated artifacts. If output can't
  be byte-compared, make it so.
- Versions are pinned in `docs/versions.md` (created in M0); don't bump casually.
- Mock APIs are paginated (≥3 pages each); pagination handling is part of every engine
  compiler's extraction contract — never special-case single-page.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (travispomeroy/dataflow) via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary — the five canonical labels, names as-is. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
