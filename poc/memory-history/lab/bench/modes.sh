#!/usr/bin/env bash
# Checks of the wait modes (Contract L6) on the compose lab. The lab must be up (compose/up.sh).
#  1. bench.py modes: each mode on envoy-gated, envoy-gated-bounded and gateway; hold time, Trino wait,
#     reservation while the query runs and after it. Writes results/modes-compose.jsonl.
#  2. For each of those queries: query_history.session_properties in history_a must have
#     required_workers_count for `wait` only.
set -euo pipefail
cd "$(dirname "$0")/../compose"
export HOST_UID=$(id -u) HOST_GID=$(id -g)
RESULTS=../results
OUT=$RESULTS/modes-compose.jsonl
: > "$OUT"
status=0
docker compose run --rm bench modes --out /results/modes-compose.jsonl "$@" || status=1

# The listener writes the row when the query ends; the bench waited for the reservations, which need the row.
echo "query_history.session_properties (history_a):"
# Read the cases first: the loop appends to the same file. psql gets /dev/null as stdin, so it does not eat the loop input.
cases=$(jq -c 'select(.kind == "mode-check")' "$OUT")
while read -r line; do
    id=$(jq -r .query_id <<<"$line")
    want=$(jq -r .expect_properties <<<"$line")
    label=$(jq -r '"\(.path) \(.how) \(.mode) \(.query)"' <<<"$line")
    props=$(docker compose exec -T postgres psql -U memoryhistory -d history_a -At \
        -c "SELECT session_properties FROM query_history WHERE query_id = '$id'" </dev/null)
    if [[ -z "$props" ]]; then
        echo "FAIL [$label] $id: no row in query_history"; status=1; continue
    fi
    has=$(jq -r 'has("required_workers_count")' <<<"$props")
    if [[ "$has" == "$want" ]]; then result=PASS; else result=FAIL; status=1; fi
    echo "$result [$label] $id $props"
    jq -c --arg r "$result" --argjson p "$props" '{kind: "history-check", case, query_id, expect_properties, history_session_properties: $p, ok: ($r == "PASS")}' <<<"$line" >> "$OUT"
done <<<"$cases"
echo "gate stats: $(curl -s "http://127.0.0.1:$(grep ^GATE_PORT= .env | cut -d= -f2)/stats")"
exit $status
