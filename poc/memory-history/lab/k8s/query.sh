#!/usr/bin/env bash
# Run one query with the Trino REST protocol. Each HTTP request is sent with curl from the
# client pod (the Trino image has curl, but no grep, sed or python), and the JSON is read
# here with jq.
#   ./query.sh <base-url> <routing-group or -> "<sql>"
# Example: ./query.sh http://gateway:8080 b "SELECT count(*) FROM nation"
# Output: query id, host of the first nextUri, data rows, final state.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

url="$1" group="$2" sql="$3"
headers=(-H "X-Trino-User: lab" -H "X-Trino-Catalog: tpch" -H "X-Trino-Schema: tiny")
if [[ "${group}" != "-" ]]; then headers+=(-H "X-Trino-Routing-Group: ${group}"); fi

resp="$(client_curl -X POST "${headers[@]}" --data "${sql}" "${url}/v1/statement")"
next="$(jq -r '.nextUri // empty' <<<"${resp}")"
echo "queryId=$(jq -r .id <<<"${resp}") firstNextUriHost=$(sed -E 's|^(https?://[^/]*).*|\1|' <<<"${next}")"
state=""
while [[ -n "${next}" ]]; do
    resp="$(client_curl -H "X-Trino-User: lab" "${next}")"
    jq -c '.data // empty' <<<"${resp}"
    state="$(jq -r '.stats.state' <<<"${resp}")"
    next="$(jq -r '.nextUri // empty' <<<"${resp}")"
done
echo "state=${state}"
