#!/usr/bin/env bash
# KEDA check for one cluster: add a reservation on predictor-<c>, watch the workers of both
# clusters grow, delete the reservation, watch cluster <c> go back to 2.
#   ./keda-check.sh [cluster=a] [predictedBytes=3400000000]
# Each line: seconds since the step began, predictor value, HPA replicas, non-terminating
# worker pods and active Trino workers, for cluster a and cluster b.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

target="${1:-a}"
bytes="${2:-3400000000}"
id="keda-check-$(date +%s)"

worker_pods() {
    kc -n "${NAMESPACE}" get pods -l "app.kubernetes.io/instance=trino-$1,app.kubernetes.io/component=worker" \
        -o jsonpath='{range .items[*]}{.metadata.deletionTimestamp}{"\n"}{end}' | grep -c '^$' || true
}
sample() {
    local start="$1" c line=""
    for c in "${CLUSTERS[@]}"; do
        local p r w t
        p="$(client_curl "http://predictor-${c}:8090/v1/required-workers" | jq -r .workers)"
        r="$(kc -n "${NAMESPACE}" get deploy "trino-${c}-worker" -o jsonpath='{.spec.replicas}')"
        w="$(worker_pods "${c}")"
        t="$(active_workers "${c}")"
        line+=" ${c}: predictor=${p} hpa=${r} pods=${w} active=${t} |"
        eval "LAST_${c}_active=${t}; LAST_${c}_pods=${w}"
    done
    echo "$(date -u +%T) t=$(( $(date +%s) - start ))s${line}"
}

expected="$(client_curl "http://predictor-${target}:8090/v1/required-workers" | jq -r .workers)"
log "before:"; sample "$(date +%s)"

start="$(date +%s)"
log "POST reservation ${id} (${bytes} bytes) on predictor-${target}"
client_curl -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
    -d "{\"id\":\"${id}\",\"predictedBytes\":${bytes},\"ttlSeconds\":1800}" "http://predictor-${target}:8090/v1/reservations"
expected="$(client_curl "http://predictor-${target}:8090/v1/required-workers" | jq -r .workers)"
log "predictor-${target} now asks for ${expected} workers"
while :; do
    sample "${start}"
    eval "a=\${LAST_${target}_active}"
    if [[ "${a}" == "${expected}" ]]; then break; fi
    if (( $(date +%s) - start > 300 )); then log "timeout"; break; fi
    sleep 2
done
log "scale-up done after $(( $(date +%s) - start )) s; holding 30 s"
for _ in 1 2 3; do sleep 10; sample "${start}"; done

start="$(date +%s)"
log "DELETE reservation ${id}"
client_curl -o /dev/null -w '%{http_code}\n' -X DELETE "http://predictor-${target}:8090/v1/reservations/${id}"
while :; do
    sample "${start}"
    eval "a=\${LAST_${target}_active}; p=\${LAST_${target}_pods}"
    if [[ "${a}" == "2" && "${p}" == "2" ]]; then break; fi
    if (( $(date +%s) - start > 400 )); then log "timeout"; break; fi
    sleep 5
done
log "scale-down done after $(( $(date +%s) - start )) s"
