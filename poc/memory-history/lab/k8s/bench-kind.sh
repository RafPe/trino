#!/usr/bin/env bash
# Runs the lab bench (../bench, contract L5) in the bench pod of the kind lab, and copies
# results/bench-kind.jsonl and results/BENCH-kind.md out. The same steps as ../bench/run.sh.
# The six paths are the kind Services (see bench_kind.py):
#   direct = trino-a:8080, gateway-plain = gateway-plain:8080, gateway = gateway:8080,
#   envoy-plain = envoy:10001, envoy-gated = envoy:10000, envoy-first-only = envoy:10002.
# The lab must be up (./up.sh). For W4 the gate is scaled to 0 and back, then run with
# GATE_DELAY_MS=2000 and set back to 0.
#
#   ./bench-kind.sh            the timed bench (milestone L7)
#   QUICK=1 ./bench-kind.sh    a short functional check: a few queries per path. The output goes
#                              to evidence/ (git-ignored), not to ../results.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

QUICK="${QUICK:-0}"
if [[ "${QUICK}" == "1" ]]; then
    OUT_DIR="${K8S_DIR}/evidence"
    NAME="bench-kind-quick"
    RUN_ARGS=(--w1-runs 16 --w2-runs 5 --w3-runs 1 --w3-rows 20000)
    W4_RUNS="${W4_RUNS:-5}"
    W4_DELAY_RUNS="${W4_DELAY_RUNS:-3}"
else
    OUT_DIR="$(cd "${K8S_DIR}/../results" && pwd)"
    NAME="bench-kind"
    RUN_ARGS=()
    W4_RUNS="${W4_RUNS:-100}"
    W4_DELAY_RUNS="${W4_DELAY_RUNS:-30}"
fi
mkdir -p "${OUT_DIR}"
JSONL="/results/${NAME}.jsonl"
REPORT="/results/${NAME/bench/BENCH}.md"

bench() { bench_exec python /bench/bench_kind.py bench "$@" --out "${JSONL}" --env kind; }
# The old gate pod keeps answering on open keep-alive connections until it is gone, so wait
# until only the new pod is left.
gate_env() {
    kc -n "${NAMESPACE}" set env deployment/gate "GATE_DELAY_MS=$1" >/dev/null
    kc -n "${NAMESPACE}" rollout status deployment/gate --timeout 2m >/dev/null
    for _ in $(seq 1 60); do
        if [[ "$(kc -n "${NAMESPACE}" get pods -l app.kubernetes.io/name=gate --no-headers 2>/dev/null | wc -l)" == "1" ]]; then break; fi
        sleep 1
    done
}
gate_replicas() {
    kc -n "${NAMESPACE}" scale deployment/gate --replicas "$1" >/dev/null
    if [[ "$1" == "0" ]]; then
        kc -n "${NAMESPACE}" wait --for=delete pod -l app.kubernetes.io/name=gate --timeout 2m >/dev/null 2>&1 || true
    else
        kc -n "${NAMESPACE}" rollout status deployment/gate --timeout 2m >/dev/null
    fi
}
# Whatever happens, leave the gate running with no delay.
restore_gate() { gate_replicas 1 || true; gate_env 0 || true; }
trap restore_gate EXIT

log "bench: ${NAME} (QUICK=${QUICK})"
bench_exec sh -c "rm -f ${JSONL} && touch ${JSONL}"
bench_exec python /bench/bench_kind.py bench smoke
bench learn
bench run --workloads "${WORKLOADS:-W1,W2,W3}" "${RUN_ARGS[@]}"
bench checks || echo "some checks failed (see the report)"

log "W4: gate scaled to 0"
gate_replicas 0
bench w4 --tag gate-stopped --runs "${W4_RUNS}"
gate_replicas 1

log "W4: GATE_DELAY_MS=2000"
gate_env 2000
bench w4 --tag gate-delay-2000ms --runs "${W4_DELAY_RUNS}"
gate_env 0

bench_exec python /bench/bench_kind.py report "${JSONL}" "${REPORT}"
bench_exec cat "${JSONL}" > "${OUT_DIR}/${NAME}.jsonl"
bench_exec cat "${REPORT}" > "${OUT_DIR}/$(basename "${REPORT}")"
log "wrote ${OUT_DIR}/${NAME}.jsonl and ${OUT_DIR}/$(basename "${REPORT}")"
