#!/usr/bin/env bash
# =============================================================================
# Cinder OS — adaptive-governor.sh
# Dynamic CPU governor control using TPS + temperature signals.
#
# Policy goals:
#   - Keep gameplay stable: favor performance when TPS degrades.
#   - Protect thermals: favor powersave when CPU temperature is high.
#   - Avoid flapping: require repeated signal confirmation and cooldown.
#
# Inputs:
#   - /opt/cinder/metrics/latest.json (preferred)
#   - /opt/cinder/logs/cinder-server.log + thermal sensors (fallback)
#
# Usage:
#   ./adaptive-governor.sh --once
#   ./adaptive-governor.sh --interval 20
#   ./adaptive-governor.sh --interval 20 --dry-run
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_LOG_DIR:=${CINDER_BASE_DIR}/logs}"
: "${CINDER_METRICS_DIR:=${CINDER_BASE_DIR}/metrics}"

: "${METRICS_LATEST:=${CINDER_METRICS_DIR}/latest.json}"
: "${PROFILER_LOG:=${CINDER_LOG_DIR}/cinder-server.log}"
: "${STATE_FILE:=${CINDER_METRICS_DIR}/adaptive-governor.state}"
: "${LOCK_FILE:=${CINDER_METRICS_DIR}/adaptive-governor.lock}"

# Thresholds (with hysteresis)
: "${TPS_LOW:=18.0}"
: "${TPS_HIGH:=19.4}"
: "${TEMP_HOT_C:=78.0}"
: "${TEMP_PERF_MAX_C:=74.0}"
: "${TEMP_COOL_C:=70.0}"

# Stability controls
: "${REQUIRED_CONFIRM_SAMPLES:=3}"
: "${MIN_SWITCH_INTERVAL_SEC:=120}"

INTERVAL_SEC=20
ONCE=false
DRY_RUN=false
QUIET=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --once)
            ONCE=true
            shift
            ;;
        --interval)
            INTERVAL_SEC="${2:?--interval requires a value}"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --quiet)
            QUIET=true
            shift
            ;;
        -h|--help)
            sed -n '1,30p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "[adaptive-governor] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

log() {
    if [[ "${QUIET}" == false ]]; then
        echo "[adaptive-governor] $*"
    fi
}

float_gt() {
    awk -v a="$1" -v b="$2" 'BEGIN {exit (a>b)?0:1}'
}

float_lt() {
    awk -v a="$1" -v b="$2" 'BEGIN {exit (a<b)?0:1}'
}

acquire_lock() {
    if ! command -v flock >/dev/null 2>&1; then
        return
    fi
    mkdir -p "$(dirname "${LOCK_FILE}")"
    exec 9>"${LOCK_FILE}"
    if ! flock -n 9; then
        log "another adaptive-governor instance is running, exiting"
        exit 0
    fi
}

current_governor() {
    local path="/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
    if [[ -f "${path}" ]]; then
        cat "${path}" 2>/dev/null || echo "unknown"
    else
        echo "unsupported"
    fi
}

available_governors() {
    local path="/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
    if [[ -f "${path}" ]]; then
        cat "${path}" 2>/dev/null || true
    fi
}

resolve_governor() {
    local requested="$1"
    local available
    available="$(available_governors)"

    if [[ -z "${available}" ]]; then
        echo "${requested}"
        return
    fi

    if echo " ${available} " | grep -q " ${requested} "; then
        echo "${requested}"
        return
    fi

    if [[ "${requested}" == "powersave" ]]; then
        if echo " ${available} " | grep -q " ondemand "; then
            echo "ondemand"
            return
        fi
        if echo " ${available} " | grep -q " schedutil "; then
            echo "schedutil"
            return
        fi
    fi

    echo "$(current_governor)"
}

set_governor_all_cpus() {
    local target="$1"
    local ok=true
    local gov_path

    for gov_path in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor; do
        if [[ ! -f "${gov_path}" ]]; then
            continue
        fi
        if [[ "${DRY_RUN}" == true ]]; then
            continue
        fi
        if ! echo "${target}" > "${gov_path}" 2>/dev/null; then
            ok=false
        fi
    done

    if [[ "${ok}" == true ]]; then
        return 0
    fi
    return 1
}

read_tps_from_log() {
    if [[ ! -f "${PROFILER_LOG}" ]]; then
        echo ""
        return
    fi

    grep 'total=.*ms' "${PROFILER_LOG}" 2>/dev/null | tail -n 180 | awk '
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
                exit
            }
            elapsed = t[n] - t[1]
            if (elapsed <= 0) elapsed = 1
            printf "%.2f", (n - 1) / elapsed
        }
    '
}

read_temp_sensor() {
    if command -v vcgencmd >/dev/null 2>&1; then
        vcgencmd measure_temp 2>/dev/null | sed -n 's/.*=\([0-9][0-9.]*\).*/\1/p'
        return
    fi
    if [[ -f /sys/class/thermal/thermal_zone0/temp ]]; then
        local milli
        milli="$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || true)"
        if [[ "${milli}" =~ ^[0-9]+$ ]]; then
            awk -v v="${milli}" 'BEGIN {printf "%.1f", v/1000}'
        fi
    fi
}

read_signals() {
    SIGNAL_TPS=""
    SIGNAL_TEMP_C=""

    if [[ -f "${METRICS_LATEST}" ]] && command -v jq >/dev/null 2>&1; then
        SIGNAL_TPS="$(jq -r '.runtime.tps // empty' "${METRICS_LATEST}" 2>/dev/null || true)"
        SIGNAL_TEMP_C="$(jq -r '.system.cpu_temp_c // empty' "${METRICS_LATEST}" 2>/dev/null || true)"
    fi

    if [[ -z "${SIGNAL_TPS}" || "${SIGNAL_TPS}" == "null" ]]; then
        SIGNAL_TPS="$(read_tps_from_log)"
    fi
    if [[ -z "${SIGNAL_TEMP_C}" || "${SIGNAL_TEMP_C}" == "null" ]]; then
        SIGNAL_TEMP_C="$(read_temp_sensor)"
    fi
}

load_state() {
    LAST_SWITCH_EPOCH=0
    PENDING_TARGET=""
    PENDING_COUNT=0

    if [[ -f "${STATE_FILE}" ]]; then
        # shellcheck disable=SC1090
        source "${STATE_FILE}" || true
    fi
}

save_state() {
    mkdir -p "$(dirname "${STATE_FILE}")"
    local tmp
    tmp="${STATE_FILE}.tmp"
    cat > "${tmp}" <<EOF
LAST_SWITCH_EPOCH=${LAST_SWITCH_EPOCH}
PENDING_TARGET="${PENDING_TARGET}"
PENDING_COUNT=${PENDING_COUNT}
EOF
    mv "${tmp}" "${STATE_FILE}"
}

decide_target() {
    local current="$1"
    local desired="${current}"
    local reason="hold"

    local save_gov perf_gov
    save_gov="$(resolve_governor powersave)"
    perf_gov="$(resolve_governor performance)"

    if [[ -n "${SIGNAL_TEMP_C}" ]] && float_gt "${SIGNAL_TEMP_C}" "${TEMP_HOT_C}"; then
        desired="${save_gov}"
        reason="temp_hot"
    elif [[ -n "${SIGNAL_TPS}" && -n "${SIGNAL_TEMP_C}" ]] \
        && float_lt "${SIGNAL_TPS}" "${TPS_LOW}" \
        && float_lt "${SIGNAL_TEMP_C}" "${TEMP_PERF_MAX_C}"; then
        desired="${perf_gov}"
        reason="tps_low"
    elif [[ -n "${SIGNAL_TPS}" && -n "${SIGNAL_TEMP_C}" ]] \
        && float_gt "${SIGNAL_TPS}" "${TPS_HIGH}" \
        && float_lt "${SIGNAL_TEMP_C}" "${TEMP_COOL_C}"; then
        desired="${save_gov}"
        reason="tps_stable_cool"
    fi

    DECISION_TARGET="${desired}"
    DECISION_REASON="${reason}"
}

apply_policy_once() {
    local now current elapsed
    now="$(date +%s)"
    current="$(current_governor)"

    if [[ "${current}" == "unsupported" ]]; then
        log "cpufreq governor interface unavailable on this host"
        return 0
    fi

    read_signals
    decide_target "${current}"

    if [[ "${DECISION_TARGET}" == "${current}" ]]; then
        PENDING_TARGET=""
        PENDING_COUNT=0
        save_state
        log "hold governor=${current} tps=${SIGNAL_TPS:-na} temp_c=${SIGNAL_TEMP_C:-na} reason=${DECISION_REASON}"
        return 0
    fi

    if [[ "${PENDING_TARGET}" == "${DECISION_TARGET}" ]]; then
        PENDING_COUNT=$(( PENDING_COUNT + 1 ))
    else
        PENDING_TARGET="${DECISION_TARGET}"
        PENDING_COUNT=1
    fi

    if (( PENDING_COUNT < REQUIRED_CONFIRM_SAMPLES )); then
        save_state
        log "pending target=${DECISION_TARGET} confirmations=${PENDING_COUNT}/${REQUIRED_CONFIRM_SAMPLES}"
        return 0
    fi

    elapsed=$(( now - LAST_SWITCH_EPOCH ))
    if (( LAST_SWITCH_EPOCH > 0 && elapsed < MIN_SWITCH_INTERVAL_SEC )); then
        save_state
        log "switch cooldown active (${elapsed}s/${MIN_SWITCH_INTERVAL_SEC}s), keep ${current}"
        return 0
    fi

    if set_governor_all_cpus "${DECISION_TARGET}"; then
        LAST_SWITCH_EPOCH="${now}"
        log "switch ${current} -> ${DECISION_TARGET} reason=${DECISION_REASON} tps=${SIGNAL_TPS:-na} temp_c=${SIGNAL_TEMP_C:-na}${DRY_RUN:+ (dry-run)}"
    else
        log "failed to apply governor ${DECISION_TARGET}, keeping ${current}"
    fi

    PENDING_TARGET=""
    PENDING_COUNT=0
    save_state
}

acquire_lock
load_state

while true; do
    apply_policy_once
    if [[ "${ONCE}" == true ]]; then
        break
    fi
    sleep "${INTERVAL_SEC}"
done

exit 0