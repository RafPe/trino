#!/usr/bin/env bash
# Query log analyzer: normalize, fingerprint, report.
#
#   analyze.sh --input <file> --format csv|jsonl [--map field=source ...] --out <dir>
#
# Other options:
#   --state-map VALUE=FINISHED|FAILED   map a state value of the log (repeatable)
#   --delimiter <c>|tab                 CSV delimiter
#   --time-unit auto|s|ms               unit of epoch numbers
#   --large-bytes <n>                   peak of a large query (default 1 GiB)
#   --input-tolerance <f>               input size check limit (default 0.10)
#   --jar <path>                        run the fingerprint step with a local java and this jar
#   --image <name>                      image for the fingerprint step (default trino-memory-history-predictor:poc)
#
# The fingerprint step runs in Docker without a network (--network none). When the mapping names
# a fingerprint field (--map fingerprint=...), the records already have a fingerprint and the step is skipped.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE=trino-memory-history-predictor:poc
IMAGE_JAR=/opt/predictor/predictor.jar
MAIN_CLASS=io.trino.memoryhistory.predictor.FingerprintTool

usage() {
    sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

INPUT="" FORMAT="" OUT="" JAR=""
NORMALIZE_ARGS=()
REPORT_ARGS=()
HAS_FINGERPRINT=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --input) INPUT="$2"; shift 2 ;;
        --format) FORMAT="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --image) IMAGE="$2"; shift 2 ;;
        --map)
            [[ "$2" == fingerprint=* ]] && HAS_FINGERPRINT=true
            NORMALIZE_ARGS+=(--map "$2"); shift 2 ;;
        --state-map|--delimiter|--time-unit) NORMALIZE_ARGS+=("$1" "$2"); shift 2 ;;
        --large-bytes|--input-tolerance) REPORT_ARGS+=("$1" "$2"); shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown option: $1" >&2; usage ;;
    esac
done
[[ -n "${INPUT}" && -n "${FORMAT}" && -n "${OUT}" ]] || usage
[[ -f "${INPUT}" ]] || { echo "input file not found: ${INPUT}" >&2; exit 1; }

mkdir -p "${OUT}"
NORMALIZED="${OUT}/normalized.jsonl"
FINGERPRINTS="${OUT}/fingerprints.jsonl"

python3 "${SCRIPT_DIR}/normalize.py" --input "${INPUT}" --format "${FORMAT}" \
    --output "${NORMALIZED}" --stats "${OUT}/normalize-stats.json" "${NORMALIZE_ARGS[@]}"

if [[ "${HAS_FINGERPRINT}" == true ]]; then
    echo "fingerprint: the records have a fingerprint field; step skipped" >&2
    rm -f "${FINGERPRINTS}"
    python3 "${SCRIPT_DIR}/report.py" --records "${NORMALIZED}" --input-name "$(basename "${INPUT}")" --normalize-stats "${OUT}/normalize-stats.json" \
        --out "${OUT}" "${REPORT_ARGS[@]}"
    exit 0
fi

if [[ -n "${JAR}" ]]; then
    java -cp "${JAR}" "${MAIN_CLASS}" < "${NORMALIZED}" > "${FINGERPRINTS}"
else
    docker run --rm -i --network none --entrypoint java "${IMAGE}" -cp "${IMAGE_JAR}" "${MAIN_CLASS}" \
        < "${NORMALIZED}" > "${FINGERPRINTS}"
fi
echo "fingerprint: $(wc -l < "${FINGERPRINTS}") lines" >&2

python3 "${SCRIPT_DIR}/report.py" --records "${NORMALIZED}" --fingerprints "${FINGERPRINTS}" --input-name "$(basename "${INPUT}")" \
    --normalize-stats "${OUT}/normalize-stats.json" --out "${OUT}" "${REPORT_ARGS[@]}"
