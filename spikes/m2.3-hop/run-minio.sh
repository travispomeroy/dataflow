#!/usr/bin/env bash
# M2.3 spike (issue #22) — throwaway, NOT production code.
# Q2 + Q4: prove a CSV lands in the MinIO staging bucket via a minio:// VFS
# path with credentials supplied only via environment.
#
# Working mechanism (the pick — see the findings comment on issue #22):
#   1. A MinioConnectionDefinition metadata object named "minio" (fields are
#      ${HOP_MINIO_*} variable expressions) placed in the active project's
#      metadata folder. The object's NAME registers the minio:// VFS scheme.
#   2. Credentials injected as JVM system properties via the HOP_OPTIONS env
#      var (plain container env vars are NOT Hop variables; system properties
#      are).
#   3. The CSV is produced locally by the pipeline, then staged with a
#      workflow Copy Files action — a clean single-stream VFS copy.
#      Direct TextFileOutput-to-minio:// is BROKEN in Hop 2.18.1 (0-byte or
#      truncated object, pipeline still reports success) — see findings.
set -euo pipefail
cd "$(dirname "$0")"

# mock-world credentials (committed; nothing here is real)
MINIO_USER=$(grep '^MINIO_ROOT_USER=' ../../infra/.env | cut -d= -f2)
MINIO_PASS=$(grep '^MINIO_ROOT_PASSWORD=' ../../infra/.env | cut -d= -f2)
MC_IMAGE=minio/minio:RELEASE.2025-09-07T16-13-09Z   # pinned in docs/versions.md

mc_run() {
  docker run --rm --network dataflow_default --entrypoint /bin/sh "$MC_IMAGE" -c \
    "mc alias set local http://minio:9000 '$MINIO_USER' '$MINIO_PASS' >/dev/null && $1"
}

# the staging bucket is not provisioned by anything yet (M2.4 finding)
mc_run "mc mb --ignore-existing local/staging"

./run-local.sh >/dev/null   # produce out/positions-page-1.csv

# HOP_OPTIONS is the image's JVM-options env var; -XX:+AggressiveHeap is its
# stock value, preserved here because overriding the var would otherwise drop it
docker run --rm \
  --network dataflow_default \
  -v "$PWD:/files" \
  -v "$PWD/metadata/MinioConnectionDefinition:/opt/hop/config/projects/default/metadata/MinioConnectionDefinition:ro" \
  -e HOP_OPTIONS="-XX:+AggressiveHeap -DHOP_MINIO_ENDPOINT_HOSTNAME=minio -DHOP_MINIO_ENDPOINT_PORT=9000 -DHOP_MINIO_ACCESS_KEY=$MINIO_USER -DHOP_MINIO_SECRET_KEY=$MINIO_PASS" \
  --entrypoint /bin/bash \
  apache/hop:2.18.1 \
  -c 'cd /opt/hop && ./hop-run.sh --file=/files/spike-stage.hwf --runconfig=local --level=Basic'

mc_run "mc cat local/staging/spike-positions/staged/positions-page-1.csv" > /tmp/spike-staged.csv
cmp /tmp/spike-staged.csv out/positions-page-1.csv
echo "PASS: staged object byte-identical to the locally produced CSV"
