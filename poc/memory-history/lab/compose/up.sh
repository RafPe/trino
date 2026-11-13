#!/usr/bin/env bash
# Starts the lab (project mhlab) and waits until every path answers SELECT 1. Safe to run again.
set -euo pipefail
cd "$(dirname "$0")"
export HOST_UID=$(id -u) HOST_GID=$(id -g)
if ! docker image inspect trino-memory-history-gate:lab >/dev/null 2>&1; then
    ../gate/build.sh
fi
docker compose up -d --wait --wait-timeout 300 \
    postgres coordinator-a worker-a predictor-a coordinator-b worker-b predictor-b gate gateway gateway-plain envoy
docker compose run --rm init
docker compose run --rm bench smoke
