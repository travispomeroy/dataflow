# M0 — Scaffold: as-built notes

Spec: issue #1; tickets #2–#9. Everything below is what deviated from the plan, was
decided mid-flight, or will bite the next person if forgotten. What went exactly to plan
is not repeated here — see `docs/PLAN.md` M0 and the spec.

## Deviations from plan

- **Per-engine compiler package names**: the plan's `compiler-kestra` / `compiler-nifi` /
  `compiler-hop` are illegal Java package names (hyphens), so the packages are
  `compilerkestra` / `compilernifi` / `compilerhop`. Documented in each `package-info`
  and in `ModularityTests`; the logical module names in docs stay hyphenated.
- **Smoke script isn't 100% built-in `fetch`**: the two NiFi checks use `node:https`
  with `rejectUnauthorized: false` because `fetch` has no per-request escape hatch for
  NiFi's self-signed certificate. Still Node built-ins only.
- **One digest pin**: `atmoz/sftp` publishes no immutable tags at all, so it is pinned
  by digest — the sole exception to "immutable tags everywhere", recorded in
  `docs/versions.md`.
- **No Hop compose service** (per plan, but easy to forget why): nothing needs a
  long-running Hop until M5. The pinned image is proven by a throwaway container running
  `hop-run.sh --version`.

## Mid-flight decisions

- **Boundary test asserts the module set, not just `verify()`**: `ModularityTests` pins
  the exact eight modules (`allPlannedModulesExist`) so a renamed or dropped package
  fails the gate rather than silently shrinking the verified surface. Enforcement was
  proven by planting a cross-module reference to a `catalog.internal` type and watching
  `verify()` fail, then removing it.
- **Negative-auth smoke checks** (Kestra rejects unauthenticated, NiFi rejects wrong
  credentials): added because both services *silently discard* bad credential config and
  come up open or with random credentials — a positive check alone can pass against a
  misconfigured service.
- **Postgres cross-connect gate + `REVOKE CONNECT ON DATABASE ... FROM PUBLIC`**: the
  plan only stated wrong-DB failure as a rationale; the gate asserts it (kestra user
  must be *denied* at permission time on the dataflow DB).
- **Gate pins the exact Node version**: the gate originally only rejected an older
  major; tightened at close-out to require exactly the `.nvmrc` version, since
  `engine-strict` guards only the npm path, not the Node that runs the smoke script and
  fixture generator.
- **Maven wrapper is the `only-script` type** (no jar), `wrapperVersion` 3.3.4
  registered in `docs/versions.md` — a clean checkout needs only a JDK; the wrapper
  downloads pinned Maven 3.9.16 itself.

## Gotchas (will bite again)

- **NiFi single-user credentials**: password must be ≥12 characters or NiFi silently
  generates random ones. Full detail in `docs/versions.md`.
- **Hop CLIs exit 1 by design after `--version`** — the gate asserts on output, never
  on Hop's exit code.
- **Spring Modulith 2.1 API**: `ApplicationModule` has no `getName()`; use
  `getIdentifier()`. Package-info-only packages *are* detected as modules, which is what
  makes the empty-skeleton boundary test meaningful.
- **`sftp -b` implies `BatchMode=yes`**, which disables password auth; `-o BatchMode=no`
  must precede `-b` (first value wins). See the gate script comments.
- **npm config does not inherit across nested package roots**: `ui/` needs its own
  `.npmrc`/`engines` copies; the root ones do not cover it.
- **Maven Central's search index lags** — resolve latest versions from
  `repo1.maven.org` `maven-metadata.xml`, not search.
- **api/internal module layout is deliberately absent**: the modules are empty
  `package-info.java` packages. When real code lands in M1, follow Modulith's
  convention — module-root types are API, sub-packages are internal unless exposed via
  `@NamedInterface`.

## State at close

All M0 gates green via `e2e/m0-gates.sh` (world left running). Fixtures are committed
and byte-stable under regeneration; they freeze once M2 goldens exist. MUI and React
Flow are pinned in `docs/versions.md` but not installed — they enter `ui/package.json`
in M3.
