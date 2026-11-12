# Shared settings, sourced by up.sh / down.sh / demo.sh.
# Every kubectl/helm call goes through kc/hm, which pin the dedicated kubeconfig,
# so the default kubeconfig and its current context are never used or changed.

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLUSTER_NAME="mhpoc"
KUBECONFIG_FILE="${K8S_DIR}/.kubeconfig"
NAMESPACE="${NAMESPACE:-trino}"
KIND_NODE_IMAGE="${KIND_NODE_IMAGE:-kindest/node:v1.34.0}"

TRINO_IMAGE_REPO="${TRINO_IMAGE_REPO:-trino-memory-history}"
TRINO_IMAGE_TAG="${TRINO_IMAGE_TAG:-poc}"
PREDICTOR_IMAGE="${PREDICTOR_IMAGE:-}"
# Set LISTENER=1 to add values-listener.yaml (needs an image with the plugin).
LISTENER="${LISTENER:-0}"

STUB_IMAGE="python:3.13-alpine"
POSTGRES_IMAGE="postgres:17-alpine"

TRINO_CHART_REPO="https://trinodb.github.io/charts"
TRINO_CHART_VERSION="1.45.0" # appVersion 484
KEDA_CHART_REPO="https://kedacore.github.io/charts"
KEDA_CHART_VERSION="2.21.0"

kc() { kubectl --kubeconfig "${KUBECONFIG_FILE}" "$@"; }
hm() { helm --kubeconfig "${KUBECONFIG_FILE}" "$@"; }

# Run SQL with the trino CLI inside the coordinator pod. Extra args go to the CLI.
trino_sql() {
    local sql="$1"
    shift
    kc -n "${NAMESPACE}" exec deploy/trino-coordinator -c trino-coordinator -- \
        trino --output-format=TSV "$@" --execute "${sql}"
}

active_workers() {
    trino_sql "SELECT count(*) FROM system.runtime.nodes WHERE coordinator = false AND state = 'active'" 2>/dev/null | tr -d '"\r'
}

log() { echo "$(date -u +%H:%M:%S) $*"; }
