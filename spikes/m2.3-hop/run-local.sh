#!/usr/bin/env bash
# M2.3 spike (issue #22) — throwaway, NOT production code.
# Q1: bare-container hop-run invocation. Q4: compose networking to wiremock.
# Runs the hand-written pipeline in the pinned image against live WireMock,
# writing the CSV to ./out/ on the host.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out && mkdir -p out && chmod 777 out   # container user "hop" must write

docker run --rm \
  --network dataflow_default \
  -v "$PWD:/files" \
  --entrypoint /bin/bash \
  apache/hop:2.18.1 \
  -c 'cd /opt/hop && ./hop-run.sh --file=/files/spike-positions.hpl --runconfig=local --level=Basic'   # image tag pinned in docs/versions.md

echo "--- first 3 lines of out/positions-page-1.csv ---"
head -3 out/positions-page-1.csv
echo "--- data rows: $(($(wc -l < out/positions-page-1.csv) - 1))"
