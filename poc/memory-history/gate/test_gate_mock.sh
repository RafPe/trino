#!/usr/bin/env bash
# TEST ONLY: proves the gate + scaler mechanism with gate/mock_predictor.py
# (canned answers, not the real predictor) on the compose cluster.
#
#   1. heavy query, --no-gate, 2 workers      -> expected FAILED (memory error)
#   2. same query through the gate; the mock says "known, needs N workers";
#      scaler.py scales workers up; Trino waits (required_workers_count);
#                                              -> expected FINISHED
#
# Prints the two gate.py JSON lines. Needs the compose stack up (see compose/).
# Env: TRINO_IMAGE (default trinodb/trino:484), WORKERS (default 4).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
POC="$(cd "$HERE/.." && pwd)"
PY="$POC/.venv/bin/python"
export TRINO_IMAGE="${TRINO_IMAGE:-trinodb/trino:484}"
WORKERS="${WORKERS:-4}"
SERVER=http://localhost:18080
PREDICTOR=http://localhost:18090
LOG_DIR="$(mktemp -d)"
SQL_FILE="$LOG_DIR/q21.sql"
sed 's/{{nation}}/SAUDI ARABIA/' "$POC/workload/queries/tpch_q21.sql" > "$SQL_FILE"

active_workers() {
    "$PY" -c "import sys; sys.path.insert(0, '$HERE'); import gate; print(gate.active_workers('$SERVER'))"
}

wait_for_workers() {
    local want=$1
    for _ in $(seq 1 60); do
        [ "$(active_workers)" = "$want" ] && return 0
        sleep 3
    done
    echo "timeout waiting for $want active workers" >&2
    return 1
}

pids=()
cleanup() {
    for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT

echo "== reset cluster to 2 workers"
(cd "$POC/compose" && docker compose up -d --no-recreate --scale worker=2 >/dev/null 2>&1)
wait_for_workers 2

echo "== 1. ungated on 2 workers"
"$PY" "$HERE/gate.py" --server "$SERVER" --predictor "$PREDICTOR" --catalog tpch --schema sf5 --no-gate \
    --tag test=ungated -f "$SQL_FILE" | tee "$LOG_DIR/ungated.json" || true

echo "== start mock predictor (TEST ONLY) and scaler"
"$PY" "$HERE/mock_predictor.py" --port 18090 --known-workers "$WORKERS" --match "l1.receiptdate" > "$LOG_DIR/mock.log" 2>&1 &
pids+=($!)
"$PY" "$HERE/scaler.py" --predictor "$PREDICTOR" --compose-dir "$POC/compose" --down-delay 30 > "$LOG_DIR/scaler.log" 2>&1 &
pids+=($!)
sleep 2

echo "== 2. gated (mock predicts $WORKERS workers)"
"$PY" "$HERE/gate.py" --server "$SERVER" --predictor "$PREDICTOR" --catalog tpch --schema sf5 \
    --tag test=gated -f "$SQL_FILE" | tee "$LOG_DIR/gated.json" || true

echo "== wait for scale-down (down-delay 30 s)"
sleep 45
echo "== scaler log"
cat "$LOG_DIR/scaler.log"
echo "logs in $LOG_DIR"
