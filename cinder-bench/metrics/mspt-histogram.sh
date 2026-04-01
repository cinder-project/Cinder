#!/usr/bin/env bash
# =============================================================================
# Cinder Bench — mspt-histogram.sh
# Parse tick/profiler logs and emit MSPT distribution statistics.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

INPUT_FILE=""
FORMAT="table"

usage() {
    cat <<'EOF'
Usage: mspt-histogram.sh --input <file> [--format table|json]
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --input)
            INPUT_FILE="${2:?--input requires a value}"
            shift 2
            ;;
        --format)
            FORMAT="${2:?--format requires a value}"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "[mspt-histogram] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ -z "${INPUT_FILE}" || ! -f "${INPUT_FILE}" ]]; then
    echo "[mspt-histogram] valid --input file is required" >&2
    exit 1
fi

TMP_MSPT="$(mktemp)"
trap 'rm -f "${TMP_MSPT}"' EXIT

if [[ "${INPUT_FILE}" == *.jsonl ]]; then
    if ! command -v jq >/dev/null 2>&1; then
        echo "[mspt-histogram] jq is required for JSONL input" >&2
        exit 1
    fi
    jq -r '.runtime.mspt // empty' "${INPUT_FILE}" > "${TMP_MSPT}"
else
    sed -n 's/.*total=\([0-9][0-9.]*\)ms.*/\1/p' "${INPUT_FILE}" > "${TMP_MSPT}"
fi

SAMPLES="$(wc -l < "${TMP_MSPT}" | tr -d ' ')"
if (( SAMPLES == 0 )); then
    echo "[mspt-histogram] no MSPT samples extracted" >&2
    exit 1
fi

MEAN="$(awk '{s+=$1} END {printf "%.3f", s/NR}' "${TMP_MSPT}")"
MAX="$(sort -nr "${TMP_MSPT}" | head -n 1)"
MIN="$(sort -n "${TMP_MSPT}" | head -n 1)"
P50_IDX=$(( (50 * (SAMPLES - 1)) / 100 + 1 ))
P95_IDX=$(( (95 * (SAMPLES - 1)) / 100 + 1 ))
P99_IDX=$(( (99 * (SAMPLES - 1)) / 100 + 1 ))
P50="$(sort -n "${TMP_MSPT}" | sed -n "${P50_IDX}p")"
P95="$(sort -n "${TMP_MSPT}" | sed -n "${P95_IDX}p")"
P99="$(sort -n "${TMP_MSPT}" | sed -n "${P99_IDX}p")"
SPIKES="$(awk '$1>=100 {c++} END {print c+0}' "${TMP_MSPT}")"

BIN_0_10="$(awk '$1<10 {c++} END {print c+0}' "${TMP_MSPT}")"
BIN_10_20="$(awk '$1>=10 && $1<20 {c++} END {print c+0}' "${TMP_MSPT}")"
BIN_20_30="$(awk '$1>=20 && $1<30 {c++} END {print c+0}' "${TMP_MSPT}")"
BIN_30_40="$(awk '$1>=30 && $1<40 {c++} END {print c+0}' "${TMP_MSPT}")"
BIN_40_50="$(awk '$1>=40 && $1<50 {c++} END {print c+0}' "${TMP_MSPT}")"
BIN_50_75="$(awk '$1>=50 && $1<75 {c++} END {print c+0}' "${TMP_MSPT}")"
BIN_75_100="$(awk '$1>=75 && $1<100 {c++} END {print c+0}' "${TMP_MSPT}")"
BIN_100P="$(awk '$1>=100 {c++} END {print c+0}' "${TMP_MSPT}")"

if [[ "${FORMAT}" == "json" ]]; then
    jq -n \
        --argjson samples "${SAMPLES}" \
        --argjson min "${MIN}" \
        --argjson mean "${MEAN}" \
        --argjson p50 "${P50}" \
        --argjson p95 "${P95}" \
        --argjson p99 "${P99}" \
        --argjson max "${MAX}" \
        --argjson spikes "${SPIKES}" \
        --argjson b0_10 "${BIN_0_10}" \
        --argjson b10_20 "${BIN_10_20}" \
        --argjson b20_30 "${BIN_20_30}" \
        --argjson b30_40 "${BIN_30_40}" \
        --argjson b40_50 "${BIN_40_50}" \
        --argjson b50_75 "${BIN_50_75}" \
        --argjson b75_100 "${BIN_75_100}" \
        --argjson b100p "${BIN_100P}" \
        '{
            sampleCount: $samples,
            minMspt: $min,
            meanMspt: $mean,
            p50Mspt: $p50,
            p95Mspt: $p95,
            p99Mspt: $p99,
            maxMspt: $max,
            spikeCount: $spikes,
            bins: {
                "0-10": $b0_10,
                "10-20": $b10_20,
                "20-30": $b20_30,
                "30-40": $b30_40,
                "40-50": $b40_50,
                "50-75": $b50_75,
                "75-100": $b75_100,
                "100+": $b100p
            }
        }'
    exit 0
fi

cat <<EOF
MSPT Histogram
==============
Samples : ${SAMPLES}
Min     : ${MIN}
Mean    : ${MEAN}
P50     : ${P50}
P95     : ${P95}
P99     : ${P99}
Max     : ${MAX}
Spikes  : ${SPIKES}

Bin counts:
  0-10    ${BIN_0_10}
  10-20   ${BIN_10_20}
  20-30   ${BIN_20_30}
  30-40   ${BIN_30_40}
  40-50   ${BIN_40_50}
  50-75   ${BIN_50_75}
  75-100  ${BIN_75_100}
  100+    ${BIN_100P}
EOF
