#!/usr/bin/env bash
# Fail-open check: scale the gate to 0 replicas, run a known query through `gateway` (group a) and
# through Envoy `gated`, then scale the gate back to 1 and run both again.
# Expected: all four queries finish. Without the gate, they have no required_workers_* properties.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

SQL="SELECT count(*) FROM orders WHERE orderkey > 4242"
SESSION="query_max_run_time=10m"
q() { # q <label> <url>
    echo "${SQL}" | bench_exec python /bench/heavy.py query --url "$2" --coordinator http://trino-a:8080 --group a \
        --catalog tpch --schema tiny --session "${SESSION}" --tag "$1" |
        jq -c '{tag, query_id, state, error, client_first_s, session}'
}
restore() { kc -n "${NAMESPACE}" scale deployment/gate --replicas 1 >/dev/null; kc -n "${NAMESPACE}" rollout status deployment/gate --timeout 2m >/dev/null; }
trap restore EXIT

# Make sure the query shape is known on cluster A.
echo "${SQL}" | bench_exec python /bench/heavy.py query --url http://trino-a:8080 --coordinator http://trino-a:8080 \
    --catalog tpch --schema tiny --tag learn >/dev/null
sleep 3

log "gate scaled to 0"
kc -n "${NAMESPACE}" scale deployment/gate --replicas 0 >/dev/null
kc -n "${NAMESPACE}" wait --for=delete pod -l app.kubernetes.io/name=gate --timeout 2m >/dev/null 2>&1 || true
log "gate pods: $(kc -n "${NAMESPACE}" get pods -l app.kubernetes.io/name=gate --no-headers 2>/dev/null | wc -l), endpoints: $(kc -n "${NAMESPACE}" get endpoints gate -o jsonpath='{.subsets}')"
q gate-0-gateway http://gateway:8080
q gate-0-envoy-gated http://envoy:10000

log "gate scaled back to 1"
restore
sleep 2
q gate-1-gateway http://gateway:8080
q gate-1-envoy-gated http://envoy:10000
log "gate stats: $(client_curl http://gate:8095/stats)"
log "reservations: $(bench_exec python /bench/heavy.py sample)"
