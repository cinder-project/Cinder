#!/usr/bin/env bash
# =============================================================================
# Cinder Control — cinder-report.sh
# Weekly performance report from metrics JSONL snapshots.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_METRICS_DIR:=${CINDER_BASE_DIR}/metrics}"
: "${CINDER_REPORT_DIR:=${CINDER_METRICS_DIR}/reports}"
: "${METRICS_FILE:=${CINDER_METRICS_DIR}/metrics.jsonl}"

TAIL_LINES=10080
OUTPUT_FILE=""
QUIET=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tail-lines)
            TAIL_LINES="${2:?--tail-lines requires a value}"
            shift 2
            ;;
        --metrics-file)
            METRICS_FILE="${2:?--metrics-file requires a value}"
            shift 2
            ;;
        --output)
            OUTPUT_FILE="${2:?--output requires a value}"
            shift 2
            ;;
        --quiet)
            QUIET=true
            shift
            ;;
        -h|--help)
            sed -n '1,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "[cinder-report] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ ! -f "${METRICS_FILE}" ]]; then
    echo "[cinder-report] metrics file not found: ${METRICS_FILE}" >&2
    exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
    echo "[cinder-report] jq is required." >&2
    exit 1
fi

mkdir -p "${CINDER_REPORT_DIR}"

if [[ -z "${OUTPUT_FILE}" ]]; then
    OUTPUT_FILE="${CINDER_REPORT_DIR}/weekly-$(date -u +%Y%m%dT%H%M%SZ).md"
fi

TMP_INPUT="$(mktemp)"
trap 'rm -f "${TMP_INPUT}"' EXIT

tail -n "${TAIL_LINES}" "${METRICS_FILE}" > "${TMP_INPUT}"

SAMPLE_COUNT="$(wc -l < "${TMP_INPUT}" | tr -d ' ')"
if [[ "${SAMPLE_COUNT}" == "0" ]]; then
    echo "[cinder-report] no data available in selected window" >&2
    exit 1
fi

SUMMARY_JSON="$(jq -s '
    def pct($arr; $p):
      if ($arr|length) == 0 then 0
      else ($arr | sort | .[((($arr|length)-1) * $p / 100 | floor)])
      end;

    . as $samples |
    ($samples | map(.runtime.tps // 0)) as $tps |
    ($samples | map(.runtime.mspt // 0)) as $mspt |
    ($samples | map(.storage.world_free_mb // 0)) as $free |
    {
      sampleCount: ($samples | length),
      firstTimestamp: ($samples[0].timestamp // ""),
      lastTimestamp: ($samples[-1].timestamp // ""),
      meanTps: (if ($tps|length)==0 then 0 else (($tps|add) / ($tps|length)) end),
      meanMspt: (if ($mspt|length)==0 then 0 else (($mspt|add) / ($mspt|length)) end),
      p95Mspt: pct($mspt; 95),
      p99Mspt: pct($mspt; 99),
      maxMspt: (if ($mspt|length)==0 then 0 else ($mspt|max) end),
      spikeCount: ($mspt | map(select(. >= 100)) | length),
      overrunCount: ($mspt | map(select(. >= 50)) | length),
      minWorldFreeMb: (if ($free|length)==0 then 0 else ($free|min) end),
      maxWorldFreeMb: (if ($free|length)==0 then 0 else ($free|max) end),
      firstWorldFreeMb: ($free[0] // 0),
      lastWorldFreeMb: ($free[-1] // 0)
    }
' "${TMP_INPUT}")"

HISTOGRAM="$(jq -s '
    map(.runtime.mspt // 0)
    | reduce .[] as $m (
        {"0-10":0,"10-20":0,"20-30":0,"30-40":0,"40-50":0,"50-75":0,"75-100":0,"100+":0};
        if $m < 10 then .["0-10"] += 1
        elif $m < 20 then .["10-20"] += 1
        elif $m < 30 then .["20-30"] += 1
        elif $m < 40 then .["30-40"] += 1
        elif $m < 50 then .["40-50"] += 1
        elif $m < 75 then .["50-75"] += 1
        elif $m < 100 then .["75-100"] += 1
        else .["100+"] += 1
        end
    )
' "${TMP_INPUT}")"

MEAN_TPS="$(echo "${SUMMARY_JSON}" | jq -r '.meanTps')"
MEAN_MSPT="$(echo "${SUMMARY_JSON}" | jq -r '.meanMspt')"
P95_MSPT="$(echo "${SUMMARY_JSON}" | jq -r '.p95Mspt')"
P99_MSPT="$(echo "${SUMMARY_JSON}" | jq -r '.p99Mspt')"
MAX_MSPT="$(echo "${SUMMARY_JSON}" | jq -r '.maxMspt')"
SAMPLES="$(echo "${SUMMARY_JSON}" | jq -r '.sampleCount')"
OVERRUNS="$(echo "${SUMMARY_JSON}" | jq -r '.overrunCount')"
SPIKES="$(echo "${SUMMARY_JSON}" | jq -r '.spikeCount')"
START_TS="$(echo "${SUMMARY_JSON}" | jq -r '.firstTimestamp')"
END_TS="$(echo "${SUMMARY_JSON}" | jq -r '.lastTimestamp')"
FIRST_FREE="$(echo "${SUMMARY_JSON}" | jq -r '.firstWorldFreeMb')"
LAST_FREE="$(echo "${SUMMARY_JSON}" | jq -r '.lastWorldFreeMb')"
MIN_FREE="$(echo "${SUMMARY_JSON}" | jq -r '.minWorldFreeMb')"
MAX_FREE="$(echo "${SUMMARY_JSON}" | jq -r '.maxWorldFreeMb')"

cat > "${OUTPUT_FILE}" <<EOF
# Cinder Weekly Performance Report

- Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)
- Window start: ${START_TS}
- Window end: ${END_TS}
- Samples: ${SAMPLES}

## Runtime Summary

- Mean TPS: $(printf '%.2f' "${MEAN_TPS}")
- Mean MSPT: $(printf '%.2f' "${MEAN_MSPT}")
- P95 MSPT: $(printf '%.2f' "${P95_MSPT}")
- P99 MSPT: $(printf '%.2f' "${P99_MSPT}")
- Max MSPT: $(printf '%.2f' "${MAX_MSPT}")
- Overruns (>=50ms): ${OVERRUNS}
- Spikes (>=100ms): ${SPIKES}

## MSPT Histogram

| Bin (ms) | Count |
|---|---:|
| 0-10 | $(echo "${HISTOGRAM}" | jq -r '.["0-10"]') |
| 10-20 | $(echo "${HISTOGRAM}" | jq -r '.["10-20"]') |
| 20-30 | $(echo "${HISTOGRAM}" | jq -r '.["20-30"]') |
| 30-40 | $(echo "${HISTOGRAM}" | jq -r '.["30-40"]') |
| 40-50 | $(echo "${HISTOGRAM}" | jq -r '.["40-50"]') |
| 50-75 | $(echo "${HISTOGRAM}" | jq -r '.["50-75"]') |
| 75-100 | $(echo "${HISTOGRAM}" | jq -r '.["75-100"]') |
| 100+ | $(echo "${HISTOGRAM}" | jq -r '.["100+"]') |

## Storage Trend

- World free MB (first sample): ${FIRST_FREE}
- World free MB (last sample): ${LAST_FREE}
- World free MB (min): ${MIN_FREE}
- World free MB (max): ${MAX_FREE}
- Delta free MB: $(( LAST_FREE - FIRST_FREE ))

EOF

if [[ "${QUIET}" == false ]]; then
    echo "[cinder-report] report written: ${OUTPUT_FILE}"
fi
