#!/usr/bin/env bash
# Milestone L8 on kind: the heavy known query in each wait mode (Contract L6), on cluster A through Envoy.
#
# Each mode gets its own new shape (TPC-H q21 at tpch.sf5 with the alias numwait_<run tag>_<mode>), so the
# runs do not change each other's history. For each mode:
#   learn: the shape directly on trino-a with 2 workers -> memory error, so predictor-a knows it as heavy
#   then, from 2 active workers and 0 reservations:
#     wait       envoy `gated` (:10000), client tag gate_mode=wait   -> Trino holds the query, then it runs
#     signal     envoy `gated`, client tag gate_mode=signal           -> starts at once on 2 workers; then a
#                second run of the same shape right after the first one ends
#     bounded40  envoy `gated-bounded` (:10003), header bounded:40    -> the gate holds until the workers are active
#     bounded5   envoy `gated-bounded`, header bounded:5              -> the hold ends after 5 s
# On `gated` the header X-Memory-Gate-Mode does not reach the gate (its allowed_headers has only x-trino-*),
# so the runs on that listener use the client tag.
#
# A timeline of both clusters (predictor, reservations, HPA, ready pods, active workers, node states of A)
# is written every 5 s.
#
#   ./demo-modes.sh                    all four modes, about 20 minutes
#   MODES="bounded5 signal" ./demo-modes.sh
# Output: ../results/modes-kind-runs.jsonl (one line per query), ../results/modes-kind-timeline.log
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

MODES="${MODES:-wait signal bounded40 bounded5}"
RESULTS="$(cd "${K8S_DIR}/../results" && pwd)"
RUNS="${RESULTS}/modes-kind-runs.jsonl"
TIMELINE="${RESULTS}/modes-kind-timeline.log"
QUERY_FILE="$(cd "${K8S_DIR}/../.." && pwd)/workload/queries/tpch_q21.sql"
SESSION="join_distribution_type=PARTITIONED,task_concurrency=4"
RUN_TAG="m$(date +%s)"

render() { # render <shape> <nation>
    sed -e "s/{{nation}}/$2/" -e "s/numwait/numwait_$1/g" "${QUERY_FILE}"
}
mark() { log "$*" | tee -a "${TIMELINE}"; }
sample() { bench_exec python /bench/heavy.py sample "$@"; }
gate_stats() { client_curl http://gate:8095/stats; }
predict() { # predict <shape>
    render "$1" "SAUDI ARABIA" | bench_exec python /bench/heavy.py predict --predictor http://predictor-a:8090 --catalog tpch --schema sf5 |
        jq -c '{known, runs, memoryFailures, predictedPeakGiB: ((.predictedPeakUserMemoryBytes // 0) / 1073741824 * 100 | round / 100), requiredWorkers, basis}'
}
ready_pods() {
    kc -n "${NAMESPACE}" get pods -l "app.kubernetes.io/instance=trino-$1,app.kubernetes.io/component=worker" -o json |
        jq '[.items[] | select(.metadata.deletionTimestamp == null) | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length'
}
timeline_line() {
    local s c out=""
    s="$(sample --states)"
    for c in a b; do
        out+=" ${c}: predictor=$(jq -r ".${c}.predictor" <<<"${s}") resv=$(jq -r ".${c}.reservations" <<<"${s}")"
        out+=" hpa=$(kc -n "${NAMESPACE}" get deploy "trino-${c}-worker" -o jsonpath='{.spec.replicas}') ready_pods=$(ready_pods "${c}")"
        out+=" active=$(jq -r ".${c}.active" <<<"${s}") |"
    done
    echo "$(date -u +%H:%M:%S)${out} a nodes: $(jq -c '.a.nodes' <<<"${s}")"
}
wait_idle() { # until each cluster has 2 active workers, 2 ready pods, 2 desired replicas and 0 reservations
    local deadline=$(( $(date +%s) + 600 )) s c ok
    while :; do
        s="$(sample)"; ok=1
        for c in "$@"; do
            if [[ "$(jq -r ".${c}.active" <<<"${s}")" != "2" || "$(jq -r ".${c}.reservations" <<<"${s}")" != "0" || "$(ready_pods "${c}")" != "2" \
                || "$(kc -n "${NAMESPACE}" get deploy "trino-${c}-worker" -o jsonpath='{.spec.replicas}')" != "2" ]]; then ok=0; fi
        done
        if (( ok )); then return 0; fi
        if (( $(date +%s) > deadline )); then log "timeout while waiting for idle clusters: ${s}"; return 1; fi
        sleep 5
    done
}
wait_reservations_zero() {
    local s
    for _ in $(seq 1 45); do
        s="$(sample)"
        if [[ "$(jq -r '.a.reservations + .b.reservations' <<<"${s}")" == "0" ]]; then log "reservations back to 0: ${s}"; return 0; fi
        sleep 2
    done
    log "reservations not back to 0 after 90 s: ${s}"
}
pod_times() {
    kc -n "${NAMESPACE}" get pods -l "app.kubernetes.io/instance=trino-$1,app.kubernetes.io/component=worker" -o json |
        jq -r '.items[] | "  \(.metadata.name) created=\(.metadata.creationTimestamp) ready=\([.status.conditions[]? | select(.type == "Ready" and .status == "True") | .lastTransitionTime][0] // "-")"'
}
hpa_events() {
    kc -n "${NAMESPACE}" get events --field-selector "involvedObject.name=keda-hpa-trino-$1-worker" \
        -o jsonpath='{range .items[*]}{.lastTimestamp} {.message}{"\n"}{end}' | sed 's/^/  /' | tail -n "${2:-3}"
}

# run_query <label> <mode> <shape> <url> <nation> [heavy.py args...]: one query, with the samples and the gate counters around it.
run_query() {
    local label="$1" mode="$2" shape="$3" url="$4" nation="$5" s0 s1 g0 g1 line
    shift 5
    s0="$(sample)"; g0="$(gate_stats)"
    mark "${label}: sent to ${url} (mode ${mode})"
    line="$(render "${shape}" "${nation}" | bench_exec python /bench/heavy.py query --url "${url}" --coordinator http://trino-a:8080 \
        --catalog tpch --schema sf5 --session "${SESSION}" --tag "${label}" "$@")"
    s1="$(sample)"; g1="$(gate_stats)"
    mark "${label}: ended $(jq -r '"\(.state) \(.error // "")"' <<<"${line}")"
    line="$(jq -c --arg mode "${mode}" --arg shape "${shape}" --argjson s0 "${s0}" --argjson s1 "${s1}" --argjson g0 "${g0}" --argjson g1 "${g1}" \
        '. + {mode: $mode, shape: $shape,
              workers_before: {a: $s0.a.active, b: $s0.b.active}, workers_after: {a: $s1.a.active, b: $s1.b.active},
              predictor_a_before: $s0.a, predictor_a_after: $s1.a,
              gate_hold_ms: ($g1.hold_ms_total - $g0.hold_ms_total),
              gate_delta: ([$g1 | to_entries[] | {key, value: (.value - ($g0[.key] // 0))} | select(.value != 0)] | from_entries)}' <<<"${line}")"
    echo "${line}" >> "${RUNS}"
    jq -c '{tag, mode, query_id, state, error: ((.error // "") | .[0:60]), gate_hold_ms, client_first_s, trino_wait: .resource_waiting_time,
            elapsed_time, peak_user_memory_gib, workers_used: .workers_used.total, workers_before: .workers_before.a, workers_after: .workers_after.a,
            required_workers_count: .session.required_workers_count}' <<<"${line}"
}

learn() { # learn <shape>: the shape directly on trino-a with 2 workers, until predictor-a knows it
    local line
    line="$(render "$1" "SAUDI ARABIA" | bench_exec python /bench/heavy.py query --url http://trino-a:8080 --coordinator http://trino-a:8080 \
        --catalog tpch --schema sf5 --session "${SESSION}" --tag "learn-$1")"
    jq -c '. + {mode: "learn"}' <<<"${line}" >> "${RUNS}"
    log "learn: $(jq -c '{query_id, state, error: ((.error // "") | .[0:40]), peak_user_memory_gib, workers_used: .workers_used.total}' <<<"${line}")"
    for _ in $(seq 1 30); do
        if [[ "$(predict "$1" | jq -r .known)" == "true" ]]; then break; fi
        sleep 2
    done
    log "predictor-a: $(predict "$1")"
}

mode_run() { # mode_run <wait|signal|bounded40|bounded5>
    local m="$1" shape="${RUN_TAG}_$1"
    mark "== mode ${m}: waiting until both clusters are idle"
    wait_idle a b
    learn "${shape}"
    wait_idle a b
    case "${m}" in
        wait)
            run_query wait wait "${shape}" http://envoy:10000 GERMANY --client-tags "mhlab-modes,gate_mode=wait" ;;
        signal)
            run_query signal-1 signal "${shape}" http://envoy:10000 GERMANY --client-tags "mhlab-modes,gate_mode=signal"
            log "predictor-a after the first run: $(predict "${shape}")"
            run_query signal-2 signal "${shape}" http://envoy:10000 BRAZIL --client-tags "mhlab-modes,gate_mode=signal" ;;
        bounded40)
            run_query bounded40 bounded:40 "${shape}" http://envoy:10003 GERMANY --gate-mode bounded:40 ;;
        bounded5)
            run_query bounded5 bounded:5 "${shape}" http://envoy:10003 GERMANY --gate-mode bounded:5 ;;
        *) echo "unknown mode ${m}" >&2; return 1 ;;
    esac
    log "trino-a worker pods:"; pod_times a
    log "HPA events of trino-a:"; hpa_events a
    wait_reservations_zero
}

: > "${RUNS}"
: > "${TIMELINE}"
log "run tag ${RUN_TAG}; modes: ${MODES}"
log "gate stats before: $(gate_stats)"
( while :; do timeline_line >> "${TIMELINE}" 2>/dev/null || true; sleep 5; done ) &
SAMPLER=$!
trap 'kill ${SAMPLER} 2>/dev/null || true' EXIT

for m in ${MODES}; do
    mode_run "${m}"
done

log "gate stats after: $(gate_stats)"
log "gate log, bounded holds:"
kc -n "${NAMESPACE}" logs deploy/gate --since=2h | grep 'mode bounded' | sed 's/^/  /' || true
ids="$(jq -r .query_id "${RUNS}" | paste -sd, - | sed "s/[^,]*/'&'/g")"
log "query_history in history_a (state, error, peak GiB, session properties):"
psql_lab history_a -F ' | ' -c "SELECT query_id, query_state, coalesce(error_code, '-'), round(peak_user_memory_bytes / 1073741824.0, 2), session_properties FROM query_history WHERE query_id IN (${ids}) ORDER BY create_time" | sed 's/^/  /'
log "waiting until both clusters are idle again"
wait_idle a b && log "idle: $(sample)"
kill "${SAMPLER}" 2>/dev/null || true
log "timeline: ${TIMELINE}"
log "queries:  ${RUNS}"
