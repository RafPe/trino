#!/usr/bin/env bash
# Show the state of the lab: pods, KEDA objects, active workers, predictor values, backends, gate counters.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

if ! kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
    echo "cluster ${CLUSTER_NAME} does not exist"
    exit 1
fi
kc -n "${NAMESPACE}" get pods -o wide
echo
kc -n "${NAMESPACE}" get scaledobject,hpa
echo
for c in "${CLUSTERS[@]}"; do
    echo "trino-${c}: active workers $(active_workers "${c}" || echo '?')," \
        "predictor-${c} $(client_curl "http://predictor-${c}:8090/v1/required-workers" || echo '?')"
done
echo
for gw in gateway gateway-plain; do
    echo "${gw} backends: $(client_curl "http://${gw}:8080/gateway/backend/all" || echo '?')"
done
echo "gate stats: $(client_curl http://gate:8095/stats || echo '?')"
