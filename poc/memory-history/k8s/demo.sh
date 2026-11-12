#!/usr/bin/env bash
# Demo / verification steps for the kind cluster created by up.sh.
#
#   ./demo.sh status            pods, ScaledObject, HPA, active Trino workers, predictor value
#   ./demo.sh set N             set the predictor STUB value to N (ConfigMap predictor-stub)
#   ./demo.sh scale-up [N]      stub -> N (default 4); time metric -> HPA -> pods -> active workers
#   ./demo.sh wait-query [N]    with the stub at its current value, submit a query that requires
#                               N workers (default 4), show it waits, then set the stub to N
#   ./demo.sh scale-down [N]    stub -> N (default 2); time the scale-down after stabilization
#   ./demo.sh stub-all          scale-up, scale-down, wait-query, scale-down (the stub evidence)
#   ./demo.sh gate              placeholder for the real-image demo (M7), see below
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

NS="${NAMESPACE}"
QUERY="SELECT count(*) FROM tpch.tiny.nation"

now() { date +%s; }
elapsed() { echo $(( $(now) - $1 )); }

predictor_value() {
    kc -n "${NS}" exec deploy/trino-coordinator -c trino-coordinator -- \
        curl -s http://predictor:8090/v1/required-workers 2>/dev/null
}

deployment_replicas() {
    kc -n "${NS}" get deployment trino-worker -o jsonpath='{.spec.replicas}'
}

running_workers() {
    # Pods that are Running and not terminating.
    kc -n "${NS}" get pods -l app.kubernetes.io/component=worker \
        -o jsonpath='{range .items[*]}{.status.phase}{" "}{.metadata.deletionTimestamp}{"\n"}{end}' |
        awk '$1 == "Running" && $2 == "" {n++} END {print n + 0}'
}

worker_pods() {
    kc -n "${NS}" get pods -l app.kubernetes.io/component=worker --no-headers 2>/dev/null | wc -l
}

set_stub() {
    kc -n "${NS}" patch configmap predictor-stub --type merge -p "{\"data\":{\"workers\":\"$1\"}}" >/dev/null
}

status() {
    kc -n "${NS}" get pods,scaledobject,hpa
    echo "predictor: $(predictor_value)"
    echo "active workers in Trino: $(active_workers)"
}

# Poll until "$@" prints the expected value; print elapsed seconds since $start.
wait_for() {
    local label="$1" expected="$2" start="$3" timeout="$4"
    shift 4
    local value
    while true; do
        value="$("$@" 2>/dev/null || true)"
        if [[ "${value}" == "${expected}" ]]; then
            log "${label} = ${expected} after $(elapsed "${start}") s"
            return 0
        fi
        if (( $(elapsed "${start}") > timeout )); then
            log "TIMEOUT waiting for ${label} = ${expected} (last: ${value})"
            return 1
        fi
        sleep 1
    done
}

workers_value() { predictor_value | sed -n 's/.*"workers": *\([0-9]*\).*/\1/p'; }

timed_scale() {
    local n="$1" timeout="$2" start
    log "setting stub to ${n}"
    start="$(now)"
    set_stub "${n}"
    wait_for "predictor /v1/required-workers" "${n}" "${start}" 120 workers_value
    wait_for "worker Deployment spec.replicas (HPA)" "${n}" "${start}" "${timeout}" deployment_replicas
    if (( n >= $(active_workers) )); then
        wait_for "worker pods created" "${n}" "${start}" "${timeout}" worker_pods
        wait_for "Running worker pods" "${n}" "${start}" "${timeout}" running_workers
        wait_for "active workers in Trino" "${n}" "${start}" "${timeout}" active_workers
    else
        wait_for "Running, non-terminating worker pods" "${n}" "${start}" "${timeout}" running_workers
        wait_for "active workers in Trino" "${n}" "${start}" "${timeout}" active_workers
        wait_for "worker pods left (terminated ones gone)" "${n}" "${start}" "${timeout}" worker_pods
    fi
}

scale_up() { timed_scale "${1:-4}" 300; status; }

scale_down() { timed_scale "${1:-2}" 600; status; }

wait_query() {
    local n="${1:-4}" out start state
    out="$(mktemp)"
    log "active workers: $(active_workers); predictor: $(predictor_value)"
    log "submitting with required_workers_count=${n}, required_workers_max_wait_time=10m: ${QUERY}"
    start="$(now)"
    kc -n "${NS}" exec deploy/trino-coordinator -c trino-coordinator -- \
        trino --session "required_workers_count=${n}" --session "required_workers_max_wait_time=10m" \
        --output-format=TSV --execute "${QUERY}" > "${out}" 2>&1 &
    local pid=$!
    sleep 20
    state="$(trino_sql "SELECT query_id || ' ' || state FROM system.runtime.queries WHERE query = '${QUERY}' AND state NOT IN ('FINISHED', 'FAILED')" | tr -d '"')"
    log "after $(elapsed "${start}") s the query is: ${state:-<not found>} (CLI still running: $(kill -0 "${pid}" 2>/dev/null && echo yes || echo no))"
    log "active workers: $(active_workers)"
    log "setting stub to ${n}"
    local scale_start
    scale_start="$(now)"
    set_stub "${n}"
    wait_for "active workers in Trino" "${n}" "${scale_start}" 300 active_workers
    wait "${pid}"
    log "query finished $(elapsed "${start}") s after submit ($(elapsed "${scale_start}") s after the stub change); result: $(tr -d '"\r' < "${out}")"
    trino_sql "SELECT query_id, state, date_diff('millisecond', created, \"end\") / 1000.0 AS seconds FROM system.runtime.queries WHERE query = '${QUERY}' ORDER BY created DESC LIMIT 1"
    rm -f "${out}"
}

gate() {
    cat <<'EOF'
Placeholder for the M7 demo with the real images (not runnable yet):
  1. Build images (poc/memory-history/docker/build-images.sh) -> trino-memory-history:poc, PREDICTOR_IMAGE.
  2. TRINO_IMAGE_REPO=trino-memory-history TRINO_IMAGE_TAG=poc PREDICTOR_IMAGE=<image> LISTENER=1 ./up.sh
  3. kubectl --kubeconfig .kubeconfig -n trino port-forward svc/trino 8080:8080 &
     kubectl --kubeconfig .kubeconfig -n trino port-forward svc/predictor 8090:8090 &
  4. Learn: run the heavy query once ungated (gate.py --no-gate ...) so it is in query_history.
  5. kubectl --kubeconfig .kubeconfig -n trino get pods -w   (in another terminal)
  6. gate.py --server http://localhost:8080 --predictor http://localhost:8090 ... -f heavy.sql
     -> reservation -> /v1/required-workers rises -> KEDA scales workers -> query runs.
EOF
}

case "${1:-status}" in
    status) status ;;
    set) set_stub "${2:?usage: demo.sh set N}"; log "stub set to $2" ;;
    scale-up) scale_up "${2:-4}" ;;
    scale-down) scale_down "${2:-2}" ;;
    wait-query) wait_query "${2:-4}" ;;
    stub-all)
        scale_up 4
        scale_down 2
        wait_query 4
        scale_down 2
        ;;
    gate) gate ;;
    *) sed -n '2,13p' "$0"; exit 1 ;;
esac
