#!/usr/bin/env bash
# M2.3 spike (issue #22) — throwaway, NOT production code.
# Reproducer for the negative finding: direct TextFileOutput -> minio:// in
# Hop 2.18.1 uploads a 0-byte object (small files; larger ones truncate at a
# buffer boundary) while the pipeline still exits 0. Close-path IOExceptions
# are printStackTrace'd and swallowed (TextFileOutputData.flushOpenFiles).
#
# This script EXITS 0 WHEN THE DEFECT REPRODUCES. If it exits 1 with
# "defect no longer reproduces", the Hop pin has been bumped past the bug and
# M2's staging strategy (local write + Copy Files) can be revisited.
set -euo pipefail
cd "$(dirname "$0")"

MINIO_USER=$(grep '^MINIO_ROOT_USER=' ../../infra/.env | cut -d= -f2)
MINIO_PASS=$(grep '^MINIO_ROOT_PASSWORD=' ../../infra/.env | cut -d= -f2)
MC_IMAGE=minio/minio:RELEASE.2025-09-07T16-13-09Z   # pinned in docs/versions.md

mc_run() {
  docker run --rm --network dataflow_default --entrypoint /bin/sh "$MC_IMAGE" -c \
    "mc alias set local http://minio:9000 '$MINIO_USER' '$MINIO_PASS' >/dev/null && $1"
}

mc_run "mc mb --ignore-existing local/staging && mc rm --force local/staging/spike-positions/direct/positions-page-1.csv >/dev/null 2>&1; true"

# hop-run exits 0 here — that is half the defect
docker run --rm \
  --network dataflow_default \
  -v "$PWD:/files" \
  -v "$PWD/metadata/MinioConnectionDefinition:/opt/hop/config/projects/default/metadata/MinioConnectionDefinition:ro" \
  -e HOP_OPTIONS="-XX:+AggressiveHeap -DHOP_MINIO_ENDPOINT_HOSTNAME=minio -DHOP_MINIO_ENDPOINT_PORT=9000 -DHOP_MINIO_ACCESS_KEY=$MINIO_USER -DHOP_MINIO_SECRET_KEY=$MINIO_PASS" \
  --entrypoint /bin/bash \
  apache/hop:2.18.1 \
  -c 'cd /opt/hop && ./hop-run.sh --file=/files/spike-positions.hpl --runconfig=local --level=Basic --parameters=OUTPUT_BASE=minio://staging/spike-positions/direct' \
  || { echo "hop-run itself failed — different failure mode than the known defect"; exit 1; }

size=$(mc_run "mc stat --json local/staging/spike-positions/direct/positions-page-1.csv" | sed -n 's/.*"size":\([0-9]*\).*/\1/p')
echo "direct-write object size: ${size:-<missing>} bytes (50 rows should be ~2700)"
if [ "${size:-0}" -eq 0 ]; then
  echo "DEFECT REPRODUCED: pipeline exited 0 but staged a 0-byte object"
  exit 0
fi
echo "defect no longer reproduces — revisit the staging strategy (issue #22 findings)"
exit 1
