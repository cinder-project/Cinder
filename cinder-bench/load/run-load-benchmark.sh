#!/usr/bin/env bash
# =============================================================================
# Cinder Bench — run-load-benchmark.sh
# Generic warmup/steady/cooldown benchmark harness for load scripts.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

SCENARIO=""
LOG_FILE="${CINDER_LOG_FILE:-/opt/cinder/logs/cinder-server.log}"
OUTPUT_FILE=""
WARMUP_SEC=30
STEADY_SEC=120
COOLDOWN_SEC=15
LOAD_START_CMD=""
LOAD_STOP_CMD=""

declare -a META=()

usage() {
    cat <<'EOF'
Usage: run-load-benchmark.sh --scenario <name> [options]

Options:
  --log <file>              Tick log file (default: /opt/cinder/logs/cinder-server.log)
  --output <file>           Result JSON output path
  --warmup-sec <n>          Warmup seconds (default: 30)
  --steady-sec <n>          Steady-state seconds (default: 120)
  --cooldown-sec <n>        Cooldown seconds (default: 15)
  --load-start-cmd <cmd>    Command run before warmup
  --load-stop-cmd <cmd>     Command run after steady phase
  --meta <key=value>        Metadata entry (repeatable)
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scenario)
            SCENARIO="${2:?--scenario requires a value}"
            shift 2
            ;;
        --log)
            LOG_FILE="${2:?--log requires a value}"
            shift 2
            ;;
        --output)
            OUTPUT_FILE="${2:?--output requires a value}"
            shift 2
            ;;
        --warmup-sec)
            WARMUP_SEC="${2:?--warmup-sec requires a value}"
            shift 2
            ;;
        --steady-sec)
            STEADY_SEC="${2:?--steady-sec requires a value}"
            shift 2
            ;;
        --cooldown-sec)
            COOLDOWN_SEC="${2:?--cooldown-sec requires a value}"
            shift 2
            ;;
        --load-start-cmd)
            LOAD_START_CMD="${2:?--load-start-cmd requires a value}"
            shift 2
            ;;
        --load-stop-cmd)
            LOAD_STOP_CMD="${2:?--load-stop-cmd requires a value}"
            shift 2
            ;;
        --meta)
            META+=("${2:?--meta requires key=value}")
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "[run-load-benchmark] Unknown argument: $1" >&2
            usage
            exit 1
            ;;
    esac
done

if [[ -z "${SCENARIO}" ]]; then
    echo "[run-load-benchmark] --scenario is required" >&2
    exit 1
fi

if [[ ! -f "${LOG_FILE}" ]]; then
    echo "[run-load-benchmark] log file not found: ${LOG_FILE}" >&2
    exit 1
fi

if [[ -z "${OUTPUT_FILE}" ]]; then
    OUTPUT_FILE="./${SCENARIO}-$(date -u +%Y%m%dT%H%M%SZ).json"
fi

mkdir -p "$(dirname "${OUTPUT_FILE}")"

TMP_TICKS="$(mktemp)"
TMP_MSPT="$(mktemp)"
trap 'rm -f "${TMP_TICKS}" "${TMP_MSPT}"' EXIT

extract_tick_lines() {
    grep 'total=.*ms' "${LOG_FILE}" > "${TMP_TICKS}" 2>/dev/null || true
}

count_tick_lines() {
    if [[ -f "${TMP_TICKS}" ]]; then
        wc -l < "${TMP_TICKS}" | tr -d ' '
    else
        echo 0
    fi
}

if [[ -n "${LOAD_START_CMD}" ]]; then
    bash -lc "${LOAD_START_CMD}"
fi

extract_tick_lines
sleep "${WARMUP_SEC}"

extract_tick_lines
START_LINE="$(count_tick_lines)"

sleep "${STEADY_SEC}"

extract_tick_lines
END_LINE="$(count_tick_lines)"

if [[ -n "${LOAD_STOP_CMD}" ]]; then
    bash -lc "${LOAD_STOP_CMD}"
fi

if (( COOLDOWN_SEC > 0 )); then
    sleep "${COOLDOWN_SEC}"
fi

if (( END_LINE <= START_LINE )); then
    echo "[run-load-benchmark] no steady-state tick lines captured" >&2
    exit 1
fi

sed -n "$((START_LINE + 1)),${END_LINE}p" "${TMP_TICKS}" \
    | sed -n 's/.*total=\([0-9][0-9.]*\)ms.*/\1/p' > "${TMP_MSPT}"

SAMPLES="$(wc -l < "${TMP_MSPT}" | tr -d ' ')"
if (( SAMPLES == 0 )); then
    echo "[run-load-benchmark] no MSPT samples extracted" >&2
    exit 1
fi

MEAN_MSPT="$(awk '{sum+=$1} END {if (NR==0) print 0; else printf "%.3f", sum/NR}' "${TMP_MSPT}")"
MAX_MSPT="$(sort -nr "${TMP_MSPT}" | head -n 1)"
P95_INDEX=$(( (95 * (SAMPLES - 1)) / 100 + 1 ))
P99_INDEX=$(( (99 * (SAMPLES - 1)) / 100 + 1 ))
P95_MSPT="$(sort -n "${TMP_MSPT}" | sed -n "${P95_INDEX}p")"
P99_MSPT="$(sort -n "${TMP_MSPT}" | sed -n "${P99_INDEX}p")"
OVERRUNS="$(awk '$1>=50 {c++} END {print c+0}' "${TMP_MSPT}")"
SPIKES="$(awk '$1>=100 {c++} END {print c+0}' "${TMP_MSPT}")"

META_JSON='{}'
for entry in "${META[@]}"; do
    key="${entry%%=*}"
    value="${entry#*=}"
    META_JSON="$(jq -c --arg k "${key}" --arg v "${value}" '. + {($k): $v}' <<< "${META_JSON}")"
done

jq -n \
    --arg scenario "${SCENARIO}" \
    --arg startedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson warmupSec "${WARMUP_SEC}" \
    --argjson steadySec "${STEADY_SEC}" \
    --argjson cooldownSec "${COOLDOWN_SEC}" \
    --argjson samples "${SAMPLES}" \
    --argjson meanMspt "${MEAN_MSPT}" \
    --argjson p95Mspt "${P95_MSPT}" \
    --argjson p99Mspt "${P99_MSPT}" \
    --argjson maxMspt "${MAX_MSPT}" \
    --argjson overruns "${OVERRUNS}" \
    --argjson spikes "${SPIKES}" \
    --argjson metadata "${META_JSON}" \
    '{
      scenario: $scenario,
      startedAtUtc: $startedAt,
      warmupSec: $warmupSec,
      steadySec: $steadySec,
      cooldownSec: $cooldownSec,
      samples: $samples,
      meanMspt: $meanMspt,
      p95Mspt: $p95Mspt,
      p99Mspt: $p99Mspt,
      maxMspt: $maxMspt,
      overrunCount: $overruns,
      spikeCount: $spikes,
      metadata: $metadata
    }' > "${OUTPUT_FILE}"

echo "[run-load-benchmark] wrote ${OUTPUT_FILE}"
