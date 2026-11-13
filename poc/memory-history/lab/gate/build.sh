#!/usr/bin/env bash
# Runs the unit tests, builds a static Linux binary on the host and the image trino-memory-history-gate:lab.
set -euo pipefail
cd "$(dirname "$0")"
GO=${GO:-$(command -v go || echo /usr/local/go/bin/go)}
ARCH=${GOARCH:-$("$GO" env GOARCH)}
"$GO" vet ./...
"$GO" test -count=1 ./...
mkdir -p bin
CGO_ENABLED=0 GOOS=linux GOARCH="$ARCH" "$GO" build -trimpath -ldflags='-s -w' -o bin/gate .
docker build -q -t "${GATE_IMAGE:-trino-memory-history-gate:lab}" .
