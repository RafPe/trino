#!/usr/bin/env bash
# Milestone L6: a heavy known query scales the right cluster, through Trino Gateway and through Envoy.
#   step 1: TPC-H q21 at tpch.sf5 directly to cluster A with 2 workers -> memory error, recorded in history_a
#   step 2: the same shape through `gateway`, routing group a -> the gate asks predictor-a, KEDA grows
#           cluster A only, Trino holds the query until the workers are there, the query finishes
#   step 3: the same shape through Envoy `gated` (cluster A)
#   step 4: the same shape through `gateway`, routing group b -> unknown on cluster B, runs unchanged
#           (it can fail on memory there; then cluster B knows it too)
# A timeline of both clusters is written every 5 s. The queries run in the bench pod (heavy.py).
#
# Each run gives the query a new column alias (numwait_r<epoch>), so it is a new shape for both
# predictors and the steps behave the same on every run.
#
#   ./demo-heavy.sh
#   SETTLE=0 ./demo-heavy.sh    do not wait for cluster A to go back to 2 workers before step 3
# Output: ../results/demo-heavy-kind-runs.jsonl (one line per query), ../results/demo-heavy-kind-timeline.log
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

SETTLE="${SETTLE:-1}"
RESULTS="$(cd "${K8S_DIR}/../results" && pwd)"
RUNS="${RESULTS}/demo-heavy-kind-runs.jsonl"
TIMELINE="${RESULTS}/demo-heavy-kind-timeline.log"
QUERY_FILE="$(cd "${K8S_DIR}/../.." && pwd)/workload/queries/tpch_q21.sql"
SESSION="join_distribution_type=PARTITIONED,task_concurrency=4"
TAG="r$(date +%s)"

render() { # render <nation>: the q21 template with this run's alias
    sed -e "s/{{nation}}/$1/" -e "s/numwait/numwait_${TAG}/g" "${QUERY_FILE}"
}
heavy() { # heavy <step label> <url> <coordinator> <group or -> <nation>
    local tag="$1" url="$2" coordinator="$3" group="$4" nation="$5" args line
    args=(--url "${url}" --coordinator "${coordinator}" --catalog tpch --schema sf5 --session "${SESSION}" --tag "${tag}")
    if [[ "${group}" != "-" ]]; then args+=(--group "${group}"); fi
    line="$(render "${nation}" | bench_exec python /bench/heavy.py query "${args[@]}")"
    echo "${line}" >> "${RUNS}"
    jq -c '{tag, query_id, state, error, client_total_s, queued_time, resource_waiting_time, elapsed_time, peak_user_memory_gib, session}' <<<"${line}"
    LAST_ID="$(jq -r .query_id <<<"${line}")"
}
predict() { # predict <a|b>
    render "SAUDI ARABIA" | bench_exec python /bench/heavy.py predict --predictor "http://predictor-$1:8090" --catalog tpch --schema sf5 |
        jq -c '{known, runs, successfulRuns, memoryFailures, predictedPeakGiB: ((.predictedPeakUserMemoryBytes // 0) / 1073741824 * 100 | round / 100), requiredWorkers, basis}'
}
sample() { bench_exec python /bench/heavy.py sample; }
ready_pods() {
    kc -n "${NAMESPACE}" get pods -l "app.kubernetes.io/instance=trino-$1,app.kubernetes.io/component=worker" -o json |
        jq '[.items[] | select(.metadata.deletionTimestamp == null) | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length'
}
timeline_line() {
    local s c out=""
    s="$(sample)"
    for c in a b; do
        out+=" ${c}: predictor=$(jq -r ".${c}.predictor" <<<"${s}") resv=$(jq -r ".${c}.reservations" <<<"${s}")"
        out+=" hpa=$(kc -n "${NAMESPACE}" get deploy "trino-${c}-worker" -o jsonpath='{.spec.replicas}') ready_pods=$(ready_pods "${c}")"
        out+=" active=$(jq -r ".${c}.active" <<<"${s}") |"
    done
    echo "$(date -u +%H:%M:%S)${out}"
}
# wait_idle <cluster...>: until each cluster has 2 active workers, 2 ready pods and 0 reservations.
wait_idle() {
    local deadline=$(( $(date +%s) + 480 )) s c ok
    while :; do
        s="$(sample)"; ok=1
        for c in "$@"; do
            if [[ "$(jq -r ".${c}.active" <<<"${s}")" != "2" || "$(jq -r ".${c}.reservations" <<<"${s}")" != "0" || "$(ready_pods "${c}")" != "2" ]]; then ok=0; fi
        done
        if (( ok )); then return 0; fi
        if (( $(date +%s) > deadline )); then log "timeout while waiting for idle clusters: ${s}"; return 1; fi
        sleep 5
    done
}
wait_reservations_zero() {
    for _ in $(seq 1 30); do
        s="$(sample)"
        if [[ "$(jq -r '.a.reservations + .b.reservations' <<<"${s}")" == "0" ]]; then log "reservations: ${s}"; return 0; fi
        sleep 2
    done
    log "reservations not back to 0 after 60 s: ${s}"
}
pod_times() { # creation and Ready times of the worker pods of cluster $1
    kc -n "${NAMESPACE}" get pods -l "app.kubernetes.io/instance=trino-$1,app.kubernetes.io/component=worker" -o json |
        jq -r '.items[] | "  \(.metadata.name) created=\(.metadata.creationTimestamp) ready=\([.status.conditions[]? | select(.type == "Ready" and .status == "True") | .lastTransitionTime][0] // "-")"'
}
hpa_events() {
    kc -n "${NAMESPACE}" get events --field-selector "involvedObject.name=keda-hpa-trino-$1-worker" \
        -o jsonpath='{range .items[*]}{.lastTimestamp} {.message}{"\n"}{end}' | sed 's/^/  /' | tail -n "${2:-4}"
}

: > "${RUNS}"
: > "${TIMELINE}"
log "run tag ${TAG}; waiting until both clusters are idle"
wait_idle a b
log "gate stats before: $(client_curl http://gate:8095/stats)"

# Worker timeline every 5 s.
( while :; do timeline_line >> "${TIMELINE}" 2>/dev/null || true; sleep 5; done ) &
SAMPLER=$!
trap 'kill ${SAMPLER} 2>/dev/null || true' EXIT

log "step 1: direct to trino-a, 2 workers (expected: memory error)"
heavy step1-direct http://trino-a:8080 http://trino-a:8080 - "SAUDI ARABIA"
for _ in $(seq 1 30); do
    if [[ "$(predict a | jq -r .known)" == "true" ]]; then break; fi
    sleep 2
done
log "predictor-a now: $(predict a)"
log "predictor-b now: $(predict b)"

log "step 2: gateway, routing group a (expected: scale-up of A only, wait, FINISHED)"
heavy step2-gateway-a http://gateway:8080 http://trino-a:8080 a "GERMANY"
log "trino-a worker pods after step 2:"; pod_times a
log "HPA events of trino-a:"; hpa_events a
wait_reservations_zero

if [[ "${SETTLE}" == "1" ]]; then
    log "waiting until cluster A is back to 2 workers before step 3"
    wait_idle a
fi
log "predictor-a now: $(predict a)"
log "step 3: envoy gated (cluster A)"
heavy step3-envoy-gated http://envoy:10000 http://trino-a:8080 - "BRAZIL"
log "trino-a worker pods after step 3:"; pod_times a
log "HPA events of trino-a:"; hpa_events a
wait_reservations_zero

log "predictor-b now: $(predict b)"
log "step 4: gateway, routing group b (unknown on B: runs unchanged)"
heavy step4-gateway-b http://gateway:8080 http://trino-b:8080 b "SAUDI ARABIA"
for _ in $(seq 1 30); do
    if [[ "$(predict b | jq -r .known)" == "true" ]]; then break; fi
    sleep 2
done
log "predictor-b now: $(predict b)"
log "HPA events of trino-b:"; hpa_events b
log "gate stats after: $(client_curl http://gate:8095/stats)"

ids="$(jq -r .query_id "${RUNS}" | paste -sd, - | sed "s/[^,]*/'&'/g")"
for c in a b; do
    log "query_history in history_${c} (state, error, peak GiB, session properties):"
    psql_lab "history_${c}" -F ' | ' -c "SELECT query_id, query_state, coalesce(error_code, '-'), round(peak_user_memory_bytes / 1073741824.0, 2), session_properties FROM query_history WHERE query_id IN (${ids}) ORDER BY create_time" | sed 's/^/  /'
done

log "waiting until both clusters are idle again"
wait_idle a b && log "idle: $(sample)"
kill "${SAMPLER}" 2>/dev/null || true
log "timeline: ${TIMELINE}"
log "queries:  ${RUNS}"
