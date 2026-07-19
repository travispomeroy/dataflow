# Version registry

Every framework, container, and toolchain version the POC uses, pinned as an exact
immutable version.

> **Bumping a version is a deliberate commit that touches this file.** No other file may
> introduce or change a version on its own — if a build file and this table disagree, this
> table wins and the discrepancy is a bug.

Rules (M0 spec): no `latest` tags, no version ranges (`^`/`~`), no bare major tags.
Digest pinning is deferred to a productionization note, with one exception below
(atmoz/sftp, which publishes no immutable tags at all).

| Component | Exact version | Where it is pinned | Date researched |
|---|---|---|---|
| Postgres | `postgres:18.4` | `infra/docker-compose.yml` (M0.2) | 2026-07-18 |
| Kestra | `kestra/kestra:v1.3.28` | `infra/docker-compose.yml` (M0.2) | 2026-07-18 |
| MinIO | `minio/minio:RELEASE.2025-09-07T16-13-09Z` | `infra/docker-compose.yml` (M0.2) | 2026-07-18 |
| WireMock | `wiremock/wiremock:3.13.2` | `infra/docker-compose.yml` (M0.4) | 2026-07-18 |
| SFTP server | `atmoz/sftp:alpine@sha256:a6cb3eb29202ca7f57e73bb7e527286e66e0e822fff65609207c7e0ef2d135a3` | `infra/docker-compose.yml` (M0.5) | 2026-07-18 |
| Apache NiFi | `apache/nifi:2.10.0` | `infra/docker-compose.yml` (M0.6) | 2026-07-18 |
| Apache Hop | `apache/hop:2.18.1` | throwaway-container image check in `e2e/` (M0.6); `DeterministicFlowYamlCompiler` `HOP_IMAGE` — the engine-runner container image (M2.5); no compose service until M5 | 2026-07-18 |
| Node.js | `24.18.0` (LTS "Krypton") | `.nvmrc` + root `package.json` `engines` (M0.1, applied); `ui/package.json` `engines` (M0.7, applied) | 2026-07-18 |
| npm | `11.16.0` (the version bundled with Node 24.18.0) | root `package.json` `engines` (M0.1, applied); `ui/package.json` (M0.7, applied) | 2026-07-18 |
| Nx | `23.1.0` | `ui/package.json` (M0.7, applied) | 2026-07-18 |
| Vite | `8.1.5` | `ui/package.json` (M0.7, applied) | 2026-07-18 |
| React / react-dom | `19.2.7` | `ui/package.json` (M0.7, applied) | 2026-07-18 |
| Vitest | `4.1.10` | `ui/package.json` (M3.2, applied) — latest stable at research date; its dependency range accepts the pinned Vite 8.1.5 (`^6.0.0 \|\| ^7.0.0 \|\| ^8.0.0`) | 2026-07-19 |
| MUI (`@mui/material`) | `9.2.0` | pinned in M0; `ui/package.json` (M3.3, applied), with its emotion styling engine pinned exact there too (see selection notes) | 2026-07-18 |
| React Flow (`@xyflow/react`) | `12.11.2` | pinned in M0; `ui/package.json` (M3.3, applied) | 2026-07-18 |
| React Router (`react-router`) | `7.18.1` | `ui/package.json` (M3.3, applied) — newest release on the v7 line the M3 spec settled on (see selection notes) | 2026-07-19 |
| TanStack Query (`@tanstack/react-query`) | `5.101.2` | `ui/package.json` (M3.3, applied) — latest stable; the v5 line is still `latest` | 2026-07-19 |
| Playwright (`@playwright/test`) | `1.61.1` | `ui/package.json` (M3.8, applied) — the package pins the exact Chromium build it drives; the gate installs it with `npx playwright install chromium` (idempotent) | 2026-07-19 |
| JDK | Temurin `25.0.3+9` (compiler release `25`) | `control-plane/pom.xml` `<maven.compiler.release>25</maven.compiler.release>` (M0.8, applied) | 2026-07-18 |
| Maven | `3.9.16` | `control-plane/` Maven wrapper `distributionUrl` (M0.8, applied) | 2026-07-18 |
| Maven wrapper | `3.3.4` (`only-script` type) | `control-plane/.mvn/wrapper/maven-wrapper.properties` `wrapperVersion` (M0.8, applied) | 2026-07-18 |
| Spring Boot | `4.1.0` | `control-plane/pom.xml` parent (M0.8, applied) | 2026-07-18 |
| Spring Modulith | `2.1.0` | `control-plane/pom.xml` via `spring-modulith-bom` (M0.8, applied) | 2026-07-18 |
| Testcontainers | `2.0.5` | `control-plane/pom.xml` via `testcontainers-bom` (M1.2, applied) | 2026-07-18 |

## How these were researched

Live registries on the research date, not memory: npm registry (`npm view <pkg> version`),
Docker Hub tags API, `repo1.maven.org` `maven-metadata.xml` (the Maven Central *search*
index was lagging and is not trustworthy for latest versions), the nodejs.org release
index, the Adoptium release API, and context7 for the NiFi docker documentation.

## Selection notes

- **Kestra**: `v1.3.28` is the newest stable line at research date (`v1.0.x` is the LTS
  line; `v2.0.0` existed only as release candidates). Full image, not `-no-plugins` — the
  compiled flows will need plugin tasks (SFTP delivery, S3/MinIO staging, docker).
- **MinIO**: `RELEASE.2025-09-07T16-13-09Z` is the last community release published to
  Docker Hub (MinIO stopped shipping newer community images). Fine for POC staging; a real
  deployment would use AWS S3 anyway (the M8 profile flip).
- **atmoz/sftp**: the repo publishes only rolling tags (`alpine`, `debian`) — there is no
  versioned tag to pin, so this is the one digest pin. Digest captured from the `alpine`
  tag on the research date. The digest is the linux/amd64 image; on arm64 hosts Docker
  runs it under emulation (verified working in M0.5) — a per-arch pin would defeat the
  point of pinning one immutable identity.
- **Postgres**: `18.4` is the newest stable (19 was beta-only at research date).
- **WireMock**: `3.13.2` is the newest stable (4.x was beta-only at research date).
- **Node**: `24.18.0` is the active LTS. `engines` is exact and `engine-strict=true` is
  set in the root `.npmrc`, so a wrong toolchain fails at `npm install` time rather than
  producing subtly different builds. `ui/` gets its own copies of both in M0.7 (npm does
  not inherit project config across nested package roots).
- **JDK/Maven** (applied in M0.8): Temurin `25.0.3+9` is the newest JDK 25 GA; compile
  with `<maven.compiler.release>25</maven.compiler.release>`. Maven `3.9.16` is the newest
  stable (3.10 / 4.0 were RC-only), pinned via the wrapper's `distributionUrl` so
  contributors never need a system Maven.
- **Spring**: Boot `4.1.0` is the `<release>` in Maven Central metadata (no 4.1.x patch
  yet at research date); Modulith `2.1.0` is the matching GA line for Boot 4.1.
- **React Router** (applied in M3.3): the M3 spec (issue #28, settled 2026-07-19) pins
  the **v7 line, library mode**. At research date npm's `latest` dist-tag is `8.2.0` —
  v8 shipped after the spec was settled and adopting it would re-litigate a settled
  decision mid-milestone; `7.18.1` is the `version-7` dist-tag (newest 7.x). The v7
  package is unified `react-router` — `react-router-dom` is a legacy alias, not needed.
- **TanStack Query** (applied in M3.3): `5.101.2` is the `latest` dist-tag (the
  registry's alpha/beta/rc dist-tags point at historic `5.0.0-*` pre-releases; no v6
  line exists at research date), matching the spec's "v5, pinned at install".
- **Playwright** (applied in M3.8): `1.61.1` is the `latest` dist-tag at research date
  (the `rc` tag points at a historic `1.18.0-rc1`; no newer line exists). Chromium-only
  per the M3 spec — the browser build is locked by the package itself, so the pin row
  covers both. `npx playwright install chromium` in the gate is a no-op once the
  version-matched browser is cached.
- **MUI styling engine** (applied in M3.3): `@mui/material` 9.2.0 supports two optional
  peer engines — emotion or Pigment CSS. Emotion is MUI's default documented engine, so
  `@emotion/react` `11.14.0` and `@emotion/styled` `11.14.1` (latest at research date)
  are pinned exact in `ui/package.json`. Support pins, not headline rows.
- **Testcontainers** (applied in M1.2): `2.0.5` is exactly what
  `spring-boot-testcontainers` 4.1.0 is built against (read from its published pom) —
  Boot 4 no longer manages Testcontainers versions itself. Note the 2.x module renames:
  the Postgres module is `testcontainers-postgresql` and the class lives in
  `org.testcontainers.postgresql`. Tests run it with the same `postgres:18.4` image
  pinned above, so `./mvnw verify` needs Docker but not the compose world. Other
  Boot-managed library versions (Flyway, JDBC driver, Jackson) deliberately get no rows
  here — this table carries headline pins only, and those flow from the Boot parent.

## Scope notes

- **TypeScript**: the only TypeScript the POC needs before M3 is the fixture generator
  (M0.3), which runs on Node 24's built-in type stripping (`node file.ts`) — deliberately
  no standalone TypeScript compiler or runner in M0, matching the generator's
  "zero data-gen deps" rule and the "plain bash + Node" gate. The UI's TypeScript arrives
  with the Nx scaffold in M0.7; Nx-generated dev tooling is pinned by that ticket's
  committed lockfile rather than rows here — this table carries the headline libraries
  only.
- **Stretch components** (Kafka M9, Relic Reporting M10, DuckDB M11) get rows added here
  when their milestones start, not before.

## NiFi 2.x single-user credentials (recorded for M0.6, the engines ticket)

Verified against the official `apache/nifi` Docker documentation via context7:

- Env vars: `SINGLE_USER_CREDENTIALS_USERNAME` and `SINGLE_USER_CREDENTIALS_PASSWORD`.
- The password **must be at least 12 characters**, otherwise NiFi silently discards the
  provided credentials and generates random ones — the M0 gate's token fetch would then
  fail, which is the desired loud failure, but pick a ≥12-char password up front.
- HTTPS is the default at port `8443` (`NIFI_WEB_HTTPS_PORT` overrides); clients must
  accept the self-signed certificate.
