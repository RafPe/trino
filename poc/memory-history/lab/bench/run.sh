#!/usr/bin/env bash
# Runs the whole bench on the compose lab and writes results/bench-compose.jsonl and results/BENCH-compose.md.
# The lab must be up (compose/up.sh). The gate is stopped and restarted for W4.
set -euo pipefail
cd "$(dirname "$0")/../compose"
export HOST_UID=$(id -u) HOST_GID=$(id -g)
RESULTS=../results
BENCH="docker compose run --rm bench"
[ -f $RESULTS/bench-compose.jsonl ] && mv $RESULTS/bench-compose.jsonl $RESULTS/bench-compose.jsonl.prev || true
touch $RESULTS/bench-compose.jsonl

$BENCH learn
$BENCH run --workloads "${WORKLOADS:-W1,W2,W3}"
$BENCH checks || echo "some checks failed (see the report)"

# W4: the gate is stopped.
docker compose stop gate
$BENCH w4 --tag gate-stopped --runs "${W4_RUNS:-100}"
docker compose up -d --wait gate

# W4: the gate waits 2 s before each answer.
GATE_DELAY_MS=2000 docker compose up -d --wait gate
$BENCH w4 --tag gate-delay-2000ms --runs "${W4_DELAY_RUNS:-30}"
docker compose up -d --wait gate

python3 -I ../bench/report.py $RESULTS/bench-compose.jsonl $RESULTS/BENCH-compose.md
echo "wrote $RESULTS/BENCH-compose.md"
