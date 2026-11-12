#!/usr/bin/env bash
# Real-image demo on the kind cluster (milestone M7):
#   1. run one heavy query without the gate on 2 workers  -> fails with a memory error (and is recorded);
#   2. run the same query shape through the gate          -> predictor asks for more workers, KEDA adds them,
#                                                            Trino holds the query until they join, the query finishes.
# Needs: ./up.sh with the real images and LISTENER=1, and the Python venv (see ../README.md).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

POC_DIR="$(cd "${K8S_DIR}/.." && pwd)"
PY="${POC_DIR}/.venv/bin/python"
TEMPLATE="${TEMPLATE:-tpch_q21}"
TRINO_PORT="${TRINO_PORT:-28080}"
PREDICTOR_PORT="${PREDICTOR_PORT:-28090}"
OUT="${POC_DIR}/results/k8s-runs.jsonl"
WATCH="${POC_DIR}/results/k8s-workers.log"

# Call kubectl directly (not the kc function) so $! is the kubectl process and cleanup really stops it.
# Stop forwards left over from an interrupted run first: a stale one keeps the port but points at a dead pod.
pkill -f "kubeconfig ${KUBECONFIG_FILE} -n ${NAMESPACE} port-forward" 2>/dev/null || true
kubectl --kubeconfig "${KUBECONFIG_FILE}" -n "${NAMESPACE}" port-forward svc/trino "${TRINO_PORT}:8080" >/dev/null 2>&1 &
PF1=$!
kubectl --kubeconfig "${KUBECONFIG_FILE}" -n "${NAMESPACE}" port-forward svc/predictor "${PREDICTOR_PORT}:8090" >/dev/null 2>&1 &
PF2=$!
WATCHER=""
cleanup() { kill ${PF1} ${PF2} ${WATCHER} 2>/dev/null || true; }
trap cleanup EXIT
# Wait until both port-forwards answer (they can take a few seconds after a rollout).
for _ in $(seq 1 60); do
    if curl -sf "http://localhost:${TRINO_PORT}/v1/info" >/dev/null && curl -sf "http://localhost:${PREDICTOR_PORT}/v1/health" >/dev/null; then
        break
    fi
    sleep 1
done
curl -sf "http://localhost:${TRINO_PORT}/v1/info" >/dev/null || { echo "port-forward to Trino is not answering" >&2; exit 1; }
curl -sf "http://localhost:${PREDICTOR_PORT}/v1/health" >/dev/null || { echo "port-forward to the predictor is not answering" >&2; exit 1; }

run() { "${PY}" "${POC_DIR}/workload/run_workload.py" --server "http://localhost:${TRINO_PORT}" \
            --predictor "http://localhost:${PREDICTOR_PORT}" --only "${TEMPLATE}" --output "${OUT}" "$@"; }

rm -f "${OUT}" "${WATCH}"
log "active workers: $(active_workers)"

log "step 1: ${TEMPLATE} without the gate (expected: memory error on 2 workers)"
run --pass learn --no-gate

# Record worker pods and active Trino workers every 5 s during the gated run.
( while true; do
      echo "$(date -u +%H:%M:%S) pods_running=$(kc -n "${NAMESPACE}" get pods -l app.kubernetes.io/component=worker --no-headers 2>/dev/null | grep -c Running) trino_active=$(active_workers) required=$(curl -s "http://localhost:${PREDICTOR_PORT}/v1/required-workers")"
      sleep 5
  done ) >> "${WATCH}" 2>/dev/null &
WATCHER=$!

log "step 2: ${TEMPLATE} through the gate (expected: scale-up, wait, FINISHED)"
run --pass predict

log "active workers now: $(active_workers)"
log "per-query results: ${OUT}"
log "worker timeline:   ${WATCH}"
