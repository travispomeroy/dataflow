---
name: verify
description: Boot the minimum world and drive the UI or API to observe a change working at runtime — without running the full gate chain.
---

# Verifying a change at runtime

The full gate chain (`e2e/m*-gates.sh`) rebuilds the world from nothing — too slow for
verifying one change. The minimum handle:

## Boot

1. **World**: `docker ps` — if `dataflow-postgres-1`, `dataflow-kestra-1`, `dataflow-wiremock-1`,
   `dataflow-sftp-1` are Up, reuse them. Wiremock's healthcheck lies on Docker Desktop
   ("unhealthy" while fine) — probe `curl http://localhost:8082/__admin/health` instead.
   Cold world: `docker compose -f infra/docker-compose.yml up -d`.
2. **Control plane**: needs the jar (`control-plane/target/control-plane-*.jar`, build with
   `./mvnw -DskipTests package` if missing). Check 8085 is free first, then
   `java -jar control-plane/target/control-plane-*.jar > <scratch>/cp.log 2>&1 &` and poll
   `http://localhost:8085/api/catalog/sources` until 200 (~15s).
3. **UI**: Node 24.18.0 via nvm (`source ~/.nvm/nvm.sh && nvm use 24.18.0`), then from `ui/`:
   `npx vite --config apps/web/vite.config.mts` → http://localhost:4200 (proxies `/api` → 8085).

## Seed

Don't build feeds by dragging on the canvas — seed via the API like the walkthroughs do:
POST `/api/dataflows` with `e2e/canonical/positions-feed.config.json` as the config (use a
name other than "Positions Feed" to avoid slug collisions with leftovers), then POST
`/deploy`. A run to SUCCEEDED takes ~10–15s on a warm Kestra; the poll ceiling is 240s.

To render a FAILED run, insert directly into the `run` table
(`docker exec dataflow-postgres-1 psql -U dataflow -d dataflow`); columns: id, dataflow_id,
kestra_execution_id (unique), status, detail, started_at, ended_at, delivered_files (jsonb
`[]`), business_date.

## Drive

Playwright MCP against http://localhost:4200. Accessibility snapshots read well; the run
history table and builder buttons all carry roles/labels.

## Leave it as found

- `POST /undeploy` then `DELETE` every seeded dataflow — a deployed feed with a Schedule
  keeps firing in the left-running world (the M2 lesson).
- Delete any non-golden delivered files from SFTP:
  `docker exec dataflow-sftp-1 sh -c 'rm -f /home/pomeroy/upload/pomeroy/<pattern>'`
  (goldens are the `*_2026-07-17.csv` five).
- Kill the control plane and vite processes; confirm 8085/4200 are free.
