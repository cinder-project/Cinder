#!/usr/bin/env bash
# =============================================================================
# Cinder Control — cinder-alert.sh
# Threshold-based alerting from latest metrics snapshot.
# Supports local log, optional webhook, optional email.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_METRICS_DIR:=${CINDER_BASE_DIR}/metrics}"
: "${LATEST_FILE:=${CINDER_METRICS_DIR}/latest.json}"
: "${ALERT_LOG:=${CINDER_METRICS_DIR}/alerts.log}"

: "${ALERT_MIN_TPS:=18.0}"
: "${ALERT_MAX_MSPT:=50.0}"
: "${ALERT_MAX_CPU_TEMP:=80.0}"

: "${ALERT_WEBHOOK_URL:=}"
: "${ALERT_EMAIL:=}"

INTERVAL_SEC=0
QUIET=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --interval)
            INTERVAL_SEC="${2:?--interval requires a value}"
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
            sed -n '1,18p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "[cinder-alert] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if ! command -v jq >/dev/null 2>&1; then
    echo "[cinder-alert] jq is required." >&2
    exit 1
fi

if [[ ! -f "${LATEST_FILE}" ]]; then
    echo "[cinder-alert] latest metrics file not found: ${LATEST_FILE}" >&2
    exit 1
fi

mkdir -p "$(dirname "${ALERT_LOG}")"

log() {
    if [[ "${QUIET}" == false ]]; then
        echo "[cinder-alert] $*"
    fi
}

emit_alert() {
    local severity="$1"
    local message="$2"
    local ts
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

    printf '%s [%s] %s\n' "${ts}" "${severity}" "${message}" >> "${ALERT_LOG}"
    log "${severity}: ${message}"

    if [[ -n "${ALERT_WEBHOOK_URL}" ]] && command -v curl >/dev/null 2>&1; then
        curl -sS -X POST -H "Content-Type: application/json" \
            -d "{\"timestamp\":\"${ts}\",\"severity\":\"${severity}\",\"message\":\"${message}\"}" \
            "${ALERT_WEBHOOK_URL}" >/dev/null || true
    fi

    if [[ -n "${ALERT_EMAIL}" ]] && command -v mail >/dev/null 2>&1; then
        printf '%s\n' "${message}" | mail -s "[Cinder Alert] ${severity}" "${ALERT_EMAIL}" || true
    fi
}

check_once() {
    local tps mspt temp alive players timestamp
    tps="$(jq -r '.runtime.tps // 0' "${LATEST_FILE}")"
    mspt="$(jq -r '.runtime.mspt // 0' "${LATEST_FILE}")"
    temp="$(jq -r '.system.cpu_temp_c // 0' "${LATEST_FILE}")"
    alive="$(jq -r '.server.alive // false' "${LATEST_FILE}")"
    players="$(jq -r '.server.players // 0' "${LATEST_FILE}")"
    timestamp="$(jq -r '.timestamp // ""' "${LATEST_FILE}")"

    if [[ "${alive}" != "true" ]]; then
        emit_alert "CRITICAL" "Server not alive at ${timestamp}"
        return
    fi

    if awk -v v="${tps}" -v min="${ALERT_MIN_TPS}" 'BEGIN{exit !(v < min)}'; then
        emit_alert "WARN" "TPS low: ${tps} < ${ALERT_MIN_TPS} (players=${players})"
    fi

    if awk -v v="${mspt}" -v max="${ALERT_MAX_MSPT}" 'BEGIN{exit !(v > max)}'; then
        emit_alert "WARN" "MSPT high: ${mspt} > ${ALERT_MAX_MSPT} (players=${players})"
    fi

    if awk -v v="${temp}" -v max="${ALERT_MAX_CPU_TEMP}" 'BEGIN{exit !(v > max)}'; then
        emit_alert "WARN" "CPU temp high: ${temp}C > ${ALERT_MAX_CPU_TEMP}C"
    fi
}

while true; do
    check_once
    if (( INTERVAL_SEC <= 0 )); then
        break
    fi
    sleep "${INTERVAL_SEC}"
done
