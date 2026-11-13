#!/usr/bin/env bash
# Create or update the kind cluster `mhlab`: KEDA, PostgreSQL (history_a, history_b, gateway),
# predictor-a and predictor-b, Trino releases trino-a and trino-b (KEDA for each),
# Trino Gateway with both clusters as backends, and a client pod.
# Safe to run again. It loads only changed images and restarts pods whose image changed.
#
#   GATEWAY_EXTERNAL=1   add values-gateway-external.yaml (phase 2: routing through the gate)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

GATEWAY_EXTERNAL="${GATEWAY_EXTERNAL:-0}"
MIN_FREE_GB=12

check_disk() {
    local free_gb
    free_gb="$(df -BG --output=avail /data | tail -1 | tr -dc '0-9')"
    log "free space on /data: ${free_gb} GB"
    if (( free_gb < MIN_FREE_GB )); then
        echo "less than ${MIN_FREE_GB} GB free on /data; stopping" >&2
        exit 1
    fi
}
check_disk

NEW_CLUSTER=0
if ! kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
    NEW_CLUSTER=1
    log "creating kind cluster ${CLUSTER_NAME}"
    kind create cluster --name "${CLUSTER_NAME}" --image "${KIND_NODE_IMAGE}" \
        --kubeconfig "${KUBECONFIG_FILE}" --config "${K8S_DIR}/kind-config.yaml" --wait 120s
else
    log "cluster ${CLUSTER_NAME} exists; refreshing ${KUBECONFIG_FILE}"
    kind get kubeconfig --name "${CLUSTER_NAME}" > "${KUBECONFIG_FILE}"
fi
chmod 600 "${KUBECONFIG_FILE}"

# `kind load docker-image` fails for the multi-arch Trino image with the containerd image
# store of Docker ("content digest ... not found"). So I stream a single-platform archive
# into the containerd of the node. The local image ID is stored as a label on the node
# image, and an unchanged image is not loaded again.
NODE="${CLUSTER_NAME}-control-plane"
PLATFORM="linux/$(docker version --format '{{.Server.Arch}}')"
RELOADED=()
load_image() {
    local image="$1" ref id
    ref="$(docker image inspect -f '{{index .RepoTags 0}}' "${image}")"
    [[ "${ref}" == */* ]] || ref="library/${ref}"
    [[ "${ref%%/*}" == *.* ]] || ref="docker.io/${ref}"
    id="$(docker image inspect -f '{{.Id}}' "${image}")"
    if docker exec "${NODE}" ctr -n k8s.io images ls "name==${ref}" 2>/dev/null | grep -q "mhlab.source-id=${id}"; then
        log "${image} already loaded"
        return
    fi
    log "loading ${image} (${PLATFORM}) into ${CLUSTER_NAME}"
    docker save --platform "${PLATFORM}" "${image}" |
        docker exec -i "${NODE}" ctr -n k8s.io images import --digests --snapshotter=overlayfs - >/dev/null
    docker exec "${NODE}" ctr -n k8s.io images label "${ref}" "mhlab.source-id=${id}" >/dev/null
    RELOADED+=("${image}")
}
# A changed image keeps its tag, so the pod specs do not change and Kubernetes keeps the
# old pods. This forces a new rollout when the image was loaded in this run.
# A new cluster has no old pods, so nothing is restarted there.
restart_if_reloaded() {
    local image="$1" loaded target
    shift
    if [[ "${NEW_CLUSTER}" == "1" ]]; then return; fi
    for loaded in "${RELOADED[@]:-}"; do
        if [[ "${loaded}" == "${image}" ]]; then
            log "${image} changed: restarting $*"
            kc -n "${NAMESPACE}" rollout restart "$@" >/dev/null
            for target in "$@"; do kc -n "${NAMESPACE}" rollout status "${target}" --timeout 5m >/dev/null; done
            return
        fi
    done
}

for image in "${TRINO_IMAGE}" "${PREDICTOR_IMAGE}" "${POSTGRES_IMAGE}" "${GATEWAY_IMAGE}"; do
    if ! docker image inspect "${image}" >/dev/null 2>&1; then
        echo "image ${image} is not present locally; build or pull it first" >&2
        exit 1
    fi
    load_image "${image}"
done
check_disk

log "installing KEDA ${KEDA_CHART_VERSION}"
hm upgrade --install keda keda --repo "${KEDA_CHART_REPO}" --version "${KEDA_CHART_VERSION}" \
    --namespace keda --create-namespace --wait --timeout 5m >/dev/null

kc create namespace "${NAMESPACE}" --dry-run=client -o yaml | kc apply -f - >/dev/null

log "deploying PostgreSQL and the client pod"
kc -n "${NAMESPACE}" apply -f "${K8S_DIR}/manifests/postgres.yaml" -f "${K8S_DIR}/manifests/client.yaml"
kc -n "${NAMESPACE}" rollout status deployment/postgres --timeout 3m

log "deploying predictors"
for c in "${CLUSTERS[@]}"; do
    sed "s|\${CLUSTER}|${c}|g" "${K8S_DIR}/manifests/predictor.yaml" | kc -n "${NAMESPACE}" apply -f -
done
for c in "${CLUSTERS[@]}"; do
    kc -n "${NAMESPACE}" rollout status "deployment/predictor-${c}" --timeout 3m
done
restart_if_reloaded "${PREDICTOR_IMAGE}" deployment/predictor-a deployment/predictor-b

log "installing Trino chart ${TRINO_CHART_VERSION}: trino-a and trino-b"
for c in "${CLUSTERS[@]}"; do
    hm upgrade --install "trino-${c}" trino --repo "${CHART_REPO}" --version "${TRINO_CHART_VERSION}" \
        --namespace "${NAMESPACE}" -f "${K8S_DIR}/values-trino.yaml" -f "${K8S_DIR}/values-trino-${c}.yaml" >/dev/null
done
for c in "${CLUSTERS[@]}"; do
    kc -n "${NAMESPACE}" rollout status "deployment/trino-${c}-coordinator" --timeout 10m
    kc -n "${NAMESPACE}" rollout status "deployment/trino-${c}-worker" --timeout 10m
done
restart_if_reloaded "${TRINO_IMAGE}" deployment/trino-a-coordinator deployment/trino-a-worker \
    deployment/trino-b-coordinator deployment/trino-b-worker deployment/client
kc -n "${NAMESPACE}" rollout status deployment/client --timeout 3m

gateway_values=(-f "${K8S_DIR}/values-gateway.yaml")
if [[ "${GATEWAY_EXTERNAL}" == "1" ]]; then gateway_values+=(-f "${K8S_DIR}/values-gateway-external.yaml"); fi
log "installing Trino Gateway chart ${GATEWAY_CHART_VERSION} (external routing: ${GATEWAY_EXTERNAL})"
hm upgrade --install gateway trino-gateway --repo "${CHART_REPO}" --version "${GATEWAY_CHART_VERSION}" \
    --namespace "${NAMESPACE}" "${gateway_values[@]}" --wait --timeout 5m >/dev/null
restart_if_reloaded "${GATEWAY_IMAGE}" deployment/gateway-trino-gateway

# Backends live in the gateway database, so register them after each start. An existing
# backend is updated, a missing one is added.
log "registering backends"
for _ in $(seq 1 60); do
    if existing="$(client_curl -f http://gateway:8080/gateway/backend/all 2>/dev/null)"; then break; fi
    sleep 2
done
for c in "${CLUSTERS[@]}"; do
    body="{\"name\":\"trino-${c}\",\"proxyTo\":\"http://trino-${c}.${NAMESPACE}.svc:8080\",\"externalUrl\":\"http://trino-${c}.${NAMESPACE}.svc:8080\",\"routingGroup\":\"${c}\",\"active\":true}"
    if [[ "${existing}" == *"\"trino-${c}\""* ]]; then action=update; else action=add; fi
    client_curl -f -o /dev/null -X POST -H 'Content-Type: application/json' -d "${body}" \
        "http://gateway:8080/gateway/backend/modify/${action}"
    log "backend trino-${c}: ${action}"
done

for c in "${CLUSTERS[@]}"; do
    for _ in $(seq 1 60); do
        n="$(active_workers "${c}" || true)"
        if [[ "${n}" =~ ^[0-9]+$ ]] && (( n >= 2 )); then break; fi
        sleep 5
    done
done
check_disk
"${K8S_DIR}/status.sh"
