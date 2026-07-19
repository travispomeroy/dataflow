#!/usr/bin/env bash
# Verify a spike run's staged bytes: usage ./03-verify.sh <runTag>
# Pulls the six objects out of MinIO (via mc inside the container), byte-compares
# the five real files against e2e/golden/delivered/, and shows the empty-class
# probe's bytes (must be header + LF only).
set -euo pipefail
SPIKE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SPIKE_DIR/../.." && pwd)"
RUN_TAG="${1:?usage: 03-verify.sh <runTag>}"

# shellcheck disable=SC1091
source "$REPO_ROOT/infra/.env"

OUT="$SPIKE_DIR/out/$RUN_TAG"
mkdir -p "$OUT"

docker exec dataflow-minio-1 sh -c \
  "mc alias set local http://localhost:9000 $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD >/dev/null"

fail=0
for cls in cash derivatives equity fixed_income other; do
  f="positions_${cls}_2026-07-17.csv"
  docker exec dataflow-minio-1 mc cat "local/staging/m42-positions-feed/$RUN_TAG/$f" > "$OUT/$f"
  if cmp -s "$OUT/$f" "$REPO_ROOT/e2e/golden/delivered/$f"; then
    echo "BYTE-IDENTICAL  $f"
  else
    echo "DIFFERS         $f"
    diff "$REPO_ROOT/e2e/golden/delivered/$f" "$OUT/$f" | head -10 || true
    fail=1
  fi
done

probe="positions_commodities_2026-07-17.csv"
docker exec dataflow-minio-1 mc cat "local/staging/m42-positions-feed/$RUN_TAG/$probe" > "$OUT/$probe"
echo "--- empty-class probe ($probe) bytes:"
od -c "$OUT/$probe" | head -12
lines=$(wc -l < "$OUT/$probe" | tr -d ' ')
header=$(head -1 "$OUT/$probe")
expected='clientId,clientName,advisorGroup,symbol,assetClass,quantity,marketValue,currency,orderId,orderSide,orderQuantity,orderStatus,tradeDate'
if [ "$lines" = "1" ] && [ "$header" = "$expected" ]; then
  echo "HEADER-ONLY OK  $probe"
else
  echo "PROBE UNEXPECTED ($lines lines)"; fail=1
fi

exit $fail
