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
| Apache Hop | `apache/hop:2.18.1` | throwaway-container image check in `e2e/` (M0.6); no compose service until M5 | 2026-07-18 |
| Node.js | `24.18.0` (LTS "Krypton") | `.nvmrc` + root `package.json` `engines` (M0.1, applied); `ui/package.json` `engines` (M0.7, applied) | 2026-07-18 |
| npm | `11.16.0` (the version bundled with Node 24.18.0) | root `package.json` `engines` (M0.1, applied); `ui/package.json` (M0.7, applied) | 2026-07-18 |
| Nx | `23.1.0` | `ui/package.json` (M0.7, applied) | 2026-07-18 |
| Vite | `8.1.5` | `ui/package.json` (M0.7, applied) | 2026-07-18 |
| React / react-dom | `19.2.7` | `ui/package.json` (M0.7, applied) | 2026-07-18 |
| MUI (`@mui/material`) | `9.2.0` | **pinned in M0, installed in M3** — enters `ui/package.json` only in M3 | 2026-07-18 |
| React Flow (`@xyflow/react`) | `12.11.2` | **pinned in M0, installed in M3** — enters `ui/package.json` only in M3 | 2026-07-18 |
| JDK | Temurin `25.0.3+9` (compiler release `25`) | `control-plane/pom.xml` `<maven.compiler.release>25</maven.compiler.release>` (M0.8, applied) | 2026-07-18 |
| Maven | `3.9.16` | `control-plane/` Maven wrapper `distributionUrl` (M0.8, applied) | 2026-07-18 |
| Maven wrapper | `3.3.4` (`only-script` type) | `control-plane/.mvn/wrapper/maven-wrapper.properties` `wrapperVersion` (M0.8, applied) | 2026-07-18 |
| Spring Boot | `4.1.0` | `control-plane/pom.xml` parent (M0.8, applied) | 2026-07-18 |
| Spring Modulith | `2.1.0` | `control-plane/pom.xml` via `spring-modulith-bom` (M0.8, applied) | 2026-07-18 |

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
  deployment would use AWS S3 anyway (the M7 profile flip).
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

## Scope notes

- **TypeScript**: the only TypeScript the POC needs before M3 is the fixture generator
  (M0.3), which runs on Node 24's built-in type stripping (`node file.ts`) — deliberately
  no standalone TypeScript compiler or runner in M0, matching the generator's
  "zero data-gen deps" rule and the "plain bash + Node" gate. The UI's TypeScript arrives
  with the Nx scaffold in M0.7; Nx-generated dev tooling is pinned by that ticket's
  committed lockfile rather than rows here — this table carries the headline libraries
  only.
- **Stretch components** (Kafka M8, Relic Reporting M9, DuckDB M10) get rows added here
  when their milestones start, not before.

## NiFi 2.x single-user credentials (recorded for M0.6, the engines ticket)

Verified against the official `apache/nifi` Docker documentation via context7:

- Env vars: `SINGLE_USER_CREDENTIALS_USERNAME` and `SINGLE_USER_CREDENTIALS_PASSWORD`.
- The password **must be at least 12 characters**, otherwise NiFi silently discards the
  provided credentials and generates random ones — the M0 gate's token fetch would then
  fail, which is the desired loud failure, but pick a ≥12-char password up front.
- HTTPS is the default at port `8443` (`NIFI_WEB_HTTPS_PORT` overrides); clients must
  accept the self-signed certificate.
