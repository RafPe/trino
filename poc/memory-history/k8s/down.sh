#!/usr/bin/env bash
# Delete the kind cluster `mhpoc` only. Other kind clusters on the host are not touched.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
    kind delete cluster --name "${CLUSTER_NAME}" --kubeconfig "${KUBECONFIG_FILE}"
else
    echo "cluster ${CLUSTER_NAME} does not exist"
fi
rm -f "${KUBECONFIG_FILE}"
