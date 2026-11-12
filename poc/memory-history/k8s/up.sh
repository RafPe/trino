#!/usr/bin/env bash
# Create (or update) the kind cluster `mhpoc` with KEDA, PostgreSQL, the predictor
# (or a stub) and Trino. Idempotent: re-run it after changing images or values.
#
#   TRINO_IMAGE_REPO   default trino-memory-history      (stock: trinodb/trino)
#   TRINO_IMAGE_TAG    default poc                       (stock: 484)
#   PREDICTOR_IMAGE    default empty -> deploy the stub  (predictor-stub.yaml)
#   LISTENER=1         add values-listener.yaml (image must contain the plugin)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

TRINO_IMAGE="${TRINO_IMAGE_REPO}:${TRINO_IMAGE_TAG}"

if ! kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
    log "creating kind cluster ${CLUSTER_NAME}"
    kind create cluster --name "${CLUSTER_NAME}" --image "${KIND_NODE_IMAGE}" \
        --kubeconfig "${KUBECONFIG_FILE}" --config "${K8S_DIR}/kind-config.yaml" --wait 120s
else
    log "cluster ${CLUSTER_NAME} exists; refreshing ${KUBECONFIG_FILE}"
    kind get kubeconfig --name "${CLUSTER_NAME}" > "${KUBECONFIG_FILE}"
fi
chmod 600 "${KUBECONFIG_FILE}"

# `kind load docker-image` fails for multi-arch images with Docker's containerd image
# store ("ctr: content digest ... not found"), so stream a single-platform archive into
# the node's containerd instead. The local image ID is stored as a label on the node's
# image so an unchanged image is not loaded again.
NODE="${CLUSTER_NAME}-control-plane"
PLATFORM="linux/$(docker version --format '{{.Server.Arch}}')"
load_image() {
    local image="$1" ref id
    ref="$(docker image inspect -f '{{index .RepoTags 0}}' "${image}")"
    [[ "${ref}" == */* ]] || ref="library/${ref}"
    [[ "${ref%%/*}" == *.* ]] || ref="docker.io/${ref}"
    id="$(docker image inspect -f '{{.Id}}' "${image}")"
    if docker exec "${NODE}" ctr -n k8s.io images ls "name==${ref}" 2>/dev/null | grep -q "mhpoc.source-id=${id}"; then
        log "${image} already loaded"
        return
    fi
    log "loading ${image} (${PLATFORM}) into ${CLUSTER_NAME}"
    docker save --platform "${PLATFORM}" "${image}" |
        docker exec -i "${NODE}" ctr -n k8s.io images import --digests --snapshotter=overlayfs - >/dev/null
    docker exec "${NODE}" ctr -n k8s.io images label "${ref}" "mhpoc.source-id=${id}" >/dev/null
}

images=("${TRINO_IMAGE}" "${POSTGRES_IMAGE}")
if [[ -n "${PREDICTOR_IMAGE}" ]]; then images+=("${PREDICTOR_IMAGE}"); else images+=("${STUB_IMAGE}"); fi
for image in "${images[@]}"; do
    if ! docker image inspect "${image}" >/dev/null 2>&1; then
        echo "image ${image} is not present locally; build or pull it first" >&2
        exit 1
    fi
    load_image "${image}"
done

log "installing KEDA ${KEDA_CHART_VERSION}"
hm upgrade --install keda keda --repo "${KEDA_CHART_REPO}" --version "${KEDA_CHART_VERSION}" \
    --namespace keda --create-namespace --wait --timeout 5m

kc create namespace "${NAMESPACE}" --dry-run=client -o yaml | kc apply -f -

log "deploying PostgreSQL"
kc -n "${NAMESPACE}" apply -f "${K8S_DIR}/manifests/postgres.yaml"

log "deploying predictor"
kc -n "${NAMESPACE}" apply -f "${K8S_DIR}/manifests/predictor-service.yaml"
if [[ -n "${PREDICTOR_IMAGE}" ]]; then
    kc -n "${NAMESPACE}" delete deployment predictor-stub --ignore-not-found
    sed "s|\${PREDICTOR_IMAGE}|${PREDICTOR_IMAGE}|" "${K8S_DIR}/manifests/predictor.yaml" | kc -n "${NAMESPACE}" apply -f -
    kc -n "${NAMESPACE}" rollout status deployment/predictor --timeout 3m
else
    kc -n "${NAMESPACE}" delete deployment predictor --ignore-not-found
    kc -n "${NAMESPACE}" apply -f "${K8S_DIR}/manifests/predictor-stub.yaml"
    kc -n "${NAMESPACE}" rollout status deployment/predictor-stub --timeout 3m
fi
kc -n "${NAMESPACE}" rollout status deployment/postgres --timeout 3m

values=(-f "${K8S_DIR}/values-trino.yaml")
if [[ "${LISTENER}" == "1" ]]; then values+=(-f "${K8S_DIR}/values-listener.yaml"); fi

log "installing Trino chart ${TRINO_CHART_VERSION} with image ${TRINO_IMAGE}"
hm upgrade --install trino trino --repo "${TRINO_CHART_REPO}" --version "${TRINO_CHART_VERSION}" \
    --namespace "${NAMESPACE}" "${values[@]}" \
    --set image.repository="${TRINO_IMAGE_REPO}" --set image.tag="${TRINO_IMAGE_TAG}" \
    --wait --timeout 10m

log "waiting for 2 active workers"
for _ in $(seq 1 60); do
    n="$(active_workers || true)"
    if [[ "${n}" =~ ^[0-9]+$ ]] && (( n >= 2 )); then break; fi
    sleep 5
done
log "active workers: $(active_workers)"
kc -n "${NAMESPACE}" get pods,scaledobject,hpa -o wide
