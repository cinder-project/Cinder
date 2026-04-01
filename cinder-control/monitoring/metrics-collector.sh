#!/usr/bin/env bash
# =============================================================================
# Cinder Control — metrics-collector.sh
# Collect runtime/system metrics and write JSONL snapshots.
#
# Output files:
#   - metrics.jsonl : append-only JSON lines (time series)
#   - latest.json   : last collected sample (single JSON object)
#
# Collected fields:
#   - runtime: tps, mspt, phase PRE/WORLD/ENTITY/CHUNK/POST
#   - server: pid/alive, estimated player count
#   - system: memory, cpu temp, load average
#   - storage: world disk usage/free
#
# Usage:
#   ./metrics-collector.sh
#   ./metrics-collector.sh --interval 60
#   ./metrics-collector.sh --interval 60 --iterations 10
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_LOG_DIR:=${CINDER_BASE_DIR}/logs}"
: "${CINDER_WORLD_DIR:=${CINDER_BASE_DIR}/world}"
: "${CINDER_METRICS_DIR:=${CINDER_BASE_DIR}/metrics}"
: "${CINDER_PROFILER_LOG:=${CINDER_LOG_DIR}/cinder-server.log}"
: "${CINDER_PID_FILE:=${CINDER_LOG_DIR}/cinder.pid}"
: "${CINDER_SERVER_PORT:=25565}"

: "${METRICS_FILE:=${CINDER_METRICS_DIR}/metrics.jsonl}"
: "${LATEST_FILE:=${CINDER_METRICS_DIR}/latest.json}"
: "${LOCK_FILE:=${CINDER_METRICS_DIR}/metrics-collector.lock}"
: "${MAX_METRIC_LINES:=10080}"

INTERVAL_SEC=0
ITERATIONS=1
QUIET=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --interval)
            INTERVAL_SEC="${2:?--interval requires a value}"
            ITERATIONS=0
            shift 2
            ;;
        --iterations)
            ITERATIONS="${2:?--iterations requires a value}"
            shift 2
            ;;
        --output)
            METRICS_FILE="${2:?--output requires a value}"
            shift 2
            ;;
        --latest)
            LATEST_FILE="${2:?--latest requires a value}"
            shift 2
            ;;
        --quiet)
            QUIET=true
            shift
            ;;
        -h|--help)
            sed -n '1,28p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "[metrics-collector] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

log() {
    if [[ "${QUIET}" == false ]]; then
        echo "[metrics-collector] $*"
    fi
}

number_or_null() {
    local v="$1"
    if [[ -z "${v}" || "${v}" == "null" ]]; then
        echo "null"
    else
        echo "${v}"
    fi
}

collect_tick_metrics() {
    local lines last_line
    lines=""
    if [[ -f "${CINDER_PROFILER_LOG}" ]]; then
        lines="$(grep 'total=.*ms' "${CINDER_PROFILER_LOG}" 2>/dev/null | tail -n 240 || true)"
    fi

    if [[ -z "${lines}" ]]; then
        RUNTIME_TPS="0.00"
        RUNTIME_MSPT="0.00"
        PHASE_PRE="0.00"
        PHASE_WORLD="0.00"
        PHASE_ENTITY="0.00"
        PHASE_CHUNK="0.00"
        PHASE_POST="0.00"
        return
    fi

    last_line="$(echo "${lines}" | tail -1)"
    RUNTIME_MSPT="$(echo "${last_line}" | sed -n 's/.*total=\([0-9][0-9.]*\)ms.*/\1/p')"
    PHASE_PRE="$(echo "${last_line}" | sed -n 's/.*PRE=\([0-9][0-9.]*\)ms.*/\1/p')"
    PHASE_WORLD="$(echo "${last_line}" | sed -n 's/.*WORLD=\([0-9][0-9.]*\)ms.*/\1/p')"
    PHASE_ENTITY="$(echo "${last_line}" | sed -n 's/.*ENTITY=\([0-9][0-9.]*\)ms.*/\1/p')"
    PHASE_CHUNK="$(echo "${last_line}" | sed -n 's/.*CHUNK=\([0-9][0-9.]*\)ms.*/\1/p')"
    PHASE_POST="$(echo "${last_line}" | sed -n 's/.*POST=\([0-9][0-9.]*\)ms.*/\1/p')"

    RUNTIME_TPS="$(echo "${lines}" | awk '
        /\[[0-9][0-9]:[0-9][0-9]:[0-9][0-9]\]/ {
            if (match($0, /\[([0-9][0-9]):([0-9][0-9]):([0-9][0-9])\]/, a)) {
                sec = a[1] * 3600 + a[2] * 60 + a[3]
                if (n > 0 && sec < t[n]) {
                    sec += 86400
                }
                t[++n] = sec
            }
        }
        END {
            if (n < 2) {
                printf "0.00"
                exit
            }
            elapsed = t[n] - t[1]
            if (elapsed <= 0) elapsed = 1
            printf "%.2f", (n - 1) / elapsed
        }
    ')"

    RUNTIME_MSPT="${RUNTIME_MSPT:-0.00}"
    PHASE_PRE="${PHASE_PRE:-0.00}"
    PHASE_WORLD="${PHASE_WORLD:-0.00}"
    PHASE_ENTITY="${PHASE_ENTITY:-0.00}"
    PHASE_CHUNK="${PHASE_CHUNK:-0.00}"
    PHASE_POST="${PHASE_POST:-0.00}"
}

collect_server_metrics() {
    SERVER_ALIVE=false
    SERVER_PID="null"

    if [[ -f "${CINDER_PID_FILE}" ]]; then
        local pid
        pid="$(cat "${CINDER_PID_FILE}" 2>/dev/null || true)"
        if [[ "${pid}" =~ ^[0-9]+$ ]]; then
            SERVER_PID="${pid}"
            if kill -0 "${pid}" 2>/dev/null; then
                SERVER_ALIVE=true
            fi
        fi
    fi

    if command -v ss >/dev/null 2>&1; then
        PLAYER_COUNT="$(ss -H -tn 2>/dev/null | awk -v port=":${CINDER_SERVER_PORT}" '$1=="ESTAB" && $4 ~ port {c++} END {print c+0}')"
    elif command -v netstat >/dev/null 2>&1; then
        PLAYER_COUNT="$(netstat -tn 2>/dev/null | awk -v port=":${CINDER_SERVER_PORT}" '$4 ~ port && $6=="ESTABLISHED" {c++} END {print c+0}')"
    else
        PLAYER_COUNT=0
    fi

    JVM_RSS_MB="null"
    if [[ "${SERVER_PID}" != "null" && "${SERVER_ALIVE}" == true ]]; then
        local rss_kb
        rss_kb="$(awk '/VmRSS/ {print $2}' "/proc/${SERVER_PID}/status" 2>/dev/null || true)"
        if [[ "${rss_kb}" =~ ^[0-9]+$ ]]; then
            JVM_RSS_MB=$(( rss_kb / 1024 ))
        fi
    fi
}

collect_system_metrics() {
    local total_kb available_kb used_kb
    read -r total_kb available_kb <<< "$(awk '
        /MemTotal/ {t=$2}
        /MemAvailable/ {a=$2}
        END {print t, a}
    ' /proc/meminfo)"

    if [[ -z "${total_kb}" || -z "${available_kb}" ]]; then
        MEM_TOTAL_MB="null"
        MEM_USED_MB="null"
        MEM_USED_PCT="null"
    else
        used_kb=$(( total_kb - available_kb ))
        MEM_TOTAL_MB=$(( total_kb / 1024 ))
        MEM_USED_MB=$(( used_kb / 1024 ))
        MEM_USED_PCT=$(( (used_kb * 100) / total_kb ))
    fi

    CPU_TEMP_C="null"
    if command -v vcgencmd >/dev/null 2>&1; then
        local temp
        temp="$(vcgencmd measure_temp 2>/dev/null | sed -n "s/.*=\([0-9][0-9.]*\).*/\1/p")"
        if [[ -n "${temp}" ]]; then
            CPU_TEMP_C="${temp}"
        fi
    elif [[ -f /sys/class/thermal/thermal_zone0/temp ]]; then
        local milli
        milli="$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || true)"
        if [[ "${milli}" =~ ^[0-9]+$ ]]; then
            CPU_TEMP_C="$(awk -v v="${milli}" 'BEGIN {printf "%.1f", v/1000}')"
        fi
    fi

    LOAD_1="$(awk '{print $1}' /proc/loadavg 2>/dev/null || echo null)"
    LOAD_5="$(awk '{print $2}' /proc/loadavg 2>/dev/null || echo null)"
}

collect_storage_metrics() {
    WORLD_USED_PCT="null"
    WORLD_FREE_MB="null"

    if [[ -d "${CINDER_WORLD_DIR}" ]]; then
        local row
        row="$(df -Pm "${CINDER_WORLD_DIR}" 2>/dev/null | awk 'NR==2 {print $4, $5}')"
        if [[ -n "${row}" ]]; then
            WORLD_FREE_MB="$(echo "${row}" | awk '{print $1}')"
            WORLD_USED_PCT="$(echo "${row}" | awk '{gsub(/%/,"",$2); print $2}')"
        fi
    fi
}

build_json_sample() {
    local ts
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    local alive_json
    if [[ "${SERVER_ALIVE}" == true ]]; then
        alive_json=true
    else
        alive_json=false
    fi

    printf '{"timestamp":"%s","server":{"alive":%s,"pid":%s,"players":%s,"port":%s,"jvm_rss_mb":%s},"runtime":{"tps":%s,"mspt":%s,"phase_ms":{"pre":%s,"world":%s,"entity":%s,"chunk":%s,"post":%s}},"system":{"cpu_temp_c":%s,"mem_total_mb":%s,"mem_used_mb":%s,"mem_used_pct":%s,"load_1":%s,"load_5":%s},"storage":{"world_used_pct":%s,"world_free_mb":%s}}' \
        "${ts}" \
        "${alive_json}" \
        "${SERVER_PID}" \
        "$(number_or_null "${PLAYER_COUNT}")" \
        "$(number_or_null "${CINDER_SERVER_PORT}")" \
        "$(number_or_null "${JVM_RSS_MB}")" \
        "$(number_or_null "${RUNTIME_TPS}")" \
        "$(number_or_null "${RUNTIME_MSPT}")" \
        "$(number_or_null "${PHASE_PRE}")" \
        "$(number_or_null "${PHASE_WORLD}")" \
        "$(number_or_null "${PHASE_ENTITY}")" \
        "$(number_or_null "${PHASE_CHUNK}")" \
        "$(number_or_null "${PHASE_POST}")" \
        "$(number_or_null "${CPU_TEMP_C}")" \
        "$(number_or_null "${MEM_TOTAL_MB}")" \
        "$(number_or_null "${MEM_USED_MB}")" \
        "$(number_or_null "${MEM_USED_PCT}")" \
        "$(number_or_null "${LOAD_1}")" \
        "$(number_or_null "${LOAD_5}")" \
        "$(number_or_null "${WORLD_USED_PCT}")" \
        "$(number_or_null "${WORLD_FREE_MB}")"
}

prune_metrics_file() {
    if [[ ! -f "${METRICS_FILE}" ]]; then
        return
    fi
    if [[ "${MAX_METRIC_LINES}" -le 0 ]]; then
        return
    fi

    local lines
    lines="$(wc -l < "${METRICS_FILE}" | tr -d ' ')"
    if (( lines <= MAX_METRIC_LINES + 200 )); then
        return
    fi

    local tmp
    tmp="$(mktemp "${METRICS_FILE}.trim.XXXXXX")"
    tail -n "${MAX_METRIC_LINES}" "${METRICS_FILE}" > "${tmp}"
    mv "${tmp}" "${METRICS_FILE}"
}

write_sample() {
    local json="$1"
    mkdir -p "$(dirname "${METRICS_FILE}")"
    mkdir -p "$(dirname "${LATEST_FILE}")"

    printf '%s\n' "${json}" >> "${METRICS_FILE}"

    local tmp_latest
    tmp_latest="${LATEST_FILE}.tmp"
    printf '%s\n' "${json}" > "${tmp_latest}"
    mv "${tmp_latest}" "${LATEST_FILE}"

    prune_metrics_file
}

acquire_lock() {
    if ! command -v flock >/dev/null 2>&1; then
        return
    fi
    mkdir -p "$(dirname "${LOCK_FILE}")"
    exec 9>"${LOCK_FILE}"
    if ! flock -n 9; then
        log "another collector instance is running, exiting"
        exit 0
    fi
}

run_once() {
    collect_tick_metrics
    collect_server_metrics
    collect_system_metrics
    collect_storage_metrics

    local json
    json="$(build_json_sample)"
    write_sample "${json}"

    log "sample written tps=${RUNTIME_TPS} mspt=${RUNTIME_MSPT} players=${PLAYER_COUNT} temp=${CPU_TEMP_C}"
}

acquire_lock

count=0
while true; do
    run_once
    count=$(( count + 1 ))

    if (( ITERATIONS > 0 && count >= ITERATIONS )); then
        break
    fi
    if (( INTERVAL_SEC <= 0 )); then
        break
    fi

    sleep "${INTERVAL_SEC}"
done

exit 0