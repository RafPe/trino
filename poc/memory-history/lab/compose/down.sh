#!/usr/bin/env bash
# Stops and removes the lab containers. Pass -v to also delete the history and gateway database.
set -euo pipefail
cd "$(dirname "$0")"
docker compose --profile tools down --remove-orphans "$@"
