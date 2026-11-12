#!/usr/bin/env bash
# Builds the memory-history fingerprint library, plugin and predictor modules, then the images
#   trino-memory-history:poc            (trinodb/trino:484 + plugin)
#   trino-memory-history-predictor:poc  (predictor service)
# Works from any directory. Needs JDK 25, mvnd (or set MVN=mvn) and docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
MVN="${MVN:-mvnd}"
FINGERPRINT_MODULE=lib/trino-memory-history-fingerprint
PLUGIN_MODULE=plugin/trino-memory-history
PREDICTOR_MODULE=service/trino-memory-history-predictor

cd "${REPO_ROOT}"
# One reactor build. -am also builds the Trino modules they need (trino-spi, trino-parser, ...):
# those SNAPSHOT artifacts are not in any remote repository, so a fresh checkout needs them built
"${MVN}" -pl "${FINGERPRINT_MODULE},${PLUGIN_MODULE},${PREDICTOR_MODULE}" -am package -DskipTests -Dair.check.skip-all=true

STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT

# Plugin: unpack the plugin zip (it holds one top-level directory)
PLUGIN_ZIP="$(ls "${REPO_ROOT}/${PLUGIN_MODULE}"/target/trino-memory-history-*.zip)"
mkdir -p "${STAGE}/plugin-zip" "${STAGE}/plugin"
python3 -m zipfile -e "${PLUGIN_ZIP}" "${STAGE}/plugin-zip"
cp "${STAGE}"/plugin-zip/*/*.jar "${STAGE}/plugin/"
chmod -R a+rX "${STAGE}/plugin"
docker build -f "${SCRIPT_DIR}/Dockerfile.trino" -t trino-memory-history:poc "${STAGE}/plugin"

# Predictor: the shaded jar holds the service and all its dependencies
mkdir -p "${STAGE}/predictor"
cp "${REPO_ROOT}/${PREDICTOR_MODULE}"/target/trino-memory-history-predictor-*-executable.jar "${STAGE}/predictor/predictor.jar"
chmod -R a+rX "${STAGE}/predictor"
docker build -f "${SCRIPT_DIR}/Dockerfile.predictor" -t trino-memory-history-predictor:poc "${STAGE}/predictor"

docker images --filter reference='trino-memory-history*:poc'
