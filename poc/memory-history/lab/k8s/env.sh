# Shared settings for the kind lab, sourced by up.sh, down.sh and status.sh.
# Every kubectl and helm call goes through kc and hm. They pin the kubeconfig file of
# this lab, so the default kubeconfig and its current context are never read or changed.

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLUSTER_NAME="mhlab"
KUBECONFIG_FILE="${K8S_DIR}/.kubeconfig"
NAMESPACE="lab"
KIND_NODE_IMAGE="${KIND_NODE_IMAGE:-kindest/node:v1.34.0}"

TRINO_IMAGE_REPO="trino-memory-history"
TRINO_IMAGE_TAG="poc"
TRINO_IMAGE="${TRINO_IMAGE_REPO}:${TRINO_IMAGE_TAG}"
PREDICTOR_IMAGE="trino-memory-history-predictor:poc"
POSTGRES_IMAGE="postgres:17-alpine"
GATEWAY_IMAGE="trinodb/trino-gateway:22"

CHART_REPO="https://trinodb.github.io/charts"
TRINO_CHART_VERSION="1.45.0"   # appVersion 484
GATEWAY_CHART_VERSION="1.22.0" # appVersion 22
KEDA_CHART_REPO="https://kedacore.github.io/charts"
KEDA_CHART_VERSION="2.21.0"

# The two Trino clusters. Release trino-<c> uses predictor-<c> and database history_<c>.
CLUSTERS=(a b)

kc() { kubectl --kubeconfig "${KUBECONFIG_FILE}" "$@"; }
hm() { helm --kubeconfig "${KUBECONFIG_FILE}" "$@"; }

# Run SQL with the trino CLI in the coordinator pod of cluster $1. Extra args go to the CLI.
trino_sql() {
    local cluster="$1" sql="$2"
    shift 2
    kc -n "${NAMESPACE}" exec "deploy/trino-${cluster}-coordinator" -c trino-coordinator -- \
        trino --output-format=TSV "$@" --execute "${sql}"
}

active_workers() {
    trino_sql "$1" "SELECT count(*) FROM system.runtime.nodes WHERE coordinator = false AND state = 'active'" 2>/dev/null | tr -d '"\r'
}

# Run SQL in PostgreSQL database $1.
psql_lab() {
    local db="$1"
    shift
    kc -n "${NAMESPACE}" exec deploy/postgres -c postgres -- psql -U trino -d "${db}" -At "$@"
}

# HTTP request from the client pod: client_curl <curl args...>
client_curl() {
    kc -n "${NAMESPACE}" exec deploy/client -c client -- curl -sS "$@"
}

log() { echo "$(date -u +%H:%M:%S) $*"; }
