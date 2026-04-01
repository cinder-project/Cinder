#!/usr/bin/env bash
# =============================================================================
# Cinder Control — metrics-reporter.sh
# Read collected metrics and emit structured output for external consumers.
#
# Sources:
#   - latest.json (single latest sample)
#   - metrics.jsonl (historical samples)
#
# Formats:
#   --json  : emit raw JSON objects
#   --line  : emit key=value records (default)
#   --tsv   : emit tab-separated rows with header
#
# Usage:
#   ./metrics-reporter.sh
#   ./metrics-reporter.sh --tail 30 --json
#   ./metrics-reporter.sh --tail 60 --tsv
#   ./metrics-reporter.sh --watch 5
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_METRICS_DIR:=${CINDER_BASE_DIR}/metrics}"

: "${METRICS_FILE:=${CINDER_METRICS_DIR}/metrics.jsonl}"
: "${LATEST_FILE:=${CINDER_METRICS_DIR}/latest.json}"

FORMAT="line"
TAIL_LINES=1
USE_HISTORY=false
WATCH_SEC=0
NO_HEADER=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --json)
            FORMAT="json"
            shift
            ;;
        --line)
            FORMAT="line"
            shift
            ;;
        --tsv)
            FORMAT="tsv"
            shift
            ;;
        --tail)
            TAIL_LINES="${2:?--tail requires a value}"
            USE_HISTORY=true
            shift 2
            ;;
        --watch)
            WATCH_SEC="${2:?--watch requires a value}"
            shift 2
            ;;
        --file)
            METRICS_FILE="${2:?--file requires a value}"
            USE_HISTORY=true
            shift 2
            ;;
        --latest)
            LATEST_FILE="${2:?--latest requires a value}"
            shift 2
            ;;
        --no-header)
            NO_HEADER=true
            shift
            ;;
        -h|--help)
            sed -n '1,28p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "[metrics-reporter] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

require_input() {
    local file="$1"
    if [[ ! -f "${file}" ]]; then
        echo "[metrics-reporter] input file not found: ${file}" >&2
        exit 1
    fi
}

records() {
    if [[ "${USE_HISTORY}" == true ]]; then
        require_input "${METRICS_FILE}"
        tail -n "${TAIL_LINES}" "${METRICS_FILE}"
    else
        require_input "${LATEST_FILE}"
        cat "${LATEST_FILE}"
    fi
}

emit_json() {
    records
}

emit_line_with_jq() {
    records | jq -r '
        "timestamp=\(.timestamp // \"\") " +
        "server_alive=\(.server.alive // false) " +
        "pid=\(.server.pid // \"null\") " +
        "players=\(.server.players // 0) " +
        "tps=\(.runtime.tps // 0) " +
        "mspt=\(.runtime.mspt // 0) " +
        "cpu_temp_c=\(.system.cpu_temp_c // \"null\") " +
        "mem_used_pct=\(.system.mem_used_pct // \"null\") " +
        "disk_used_pct=\(.storage.world_used_pct // \"null\")"
    '
}

emit_tsv_with_jq() {
    if [[ "${NO_HEADER}" == false ]]; then
        printf 'timestamp\tserver_alive\tpid\tplayers\ttps\tmspt\tcpu_temp_c\tmem_used_pct\tdisk_used_pct\n'
    fi
    records | jq -r '[
        (.timestamp // ""),
        (.server.alive // false),
        (.server.pid // "null"),
        (.server.players // 0),
        (.runtime.tps // 0),
        (.runtime.mspt // 0),
        (.system.cpu_temp_c // "null"),
        (.system.mem_used_pct // "null"),
        (.storage.world_used_pct // "null")
    ] | @tsv'
}

extract_simple() {
    local key="$1"
    local line="$2"
    echo "${line}" | sed -n "s/.*\"${key}\":\([^,}]*\).*/\1/p" | tr -d '"'
}

emit_line_without_jq() {
    records | while IFS= read -r line; do
        timestamp="$(extract_simple timestamp "${line}")"
        players="$(extract_simple players "${line}")"
        tps="$(extract_simple tps "${line}")"
        mspt="$(extract_simple mspt "${line}")"
        cpu_temp="$(extract_simple cpu_temp_c "${line}")"
        mem_pct="$(extract_simple mem_used_pct "${line}")"
        disk_pct="$(extract_simple world_used_pct "${line}")"
        pid="$(extract_simple pid "${line}")"

        printf 'timestamp=%s pid=%s players=%s tps=%s mspt=%s cpu_temp_c=%s mem_used_pct=%s disk_used_pct=%s\n' \
            "${timestamp}" "${pid}" "${players}" "${tps}" "${mspt}" "${cpu_temp}" "${mem_pct}" "${disk_pct}"
    done
}

emit_tsv_without_jq() {
    if [[ "${NO_HEADER}" == false ]]; then
        printf 'timestamp\tpid\tplayers\ttps\tmspt\tcpu_temp_c\tmem_used_pct\tdisk_used_pct\n'
    fi
    records | while IFS= read -r line; do
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$(extract_simple timestamp "${line}")" \
            "$(extract_simple pid "${line}")" \
            "$(extract_simple players "${line}")" \
            "$(extract_simple tps "${line}")" \
            "$(extract_simple mspt "${line}")" \
            "$(extract_simple cpu_temp_c "${line}")" \
            "$(extract_simple mem_used_pct "${line}")" \
            "$(extract_simple world_used_pct "${line}")"
    done
}

emit_once() {
    case "${FORMAT}" in
        json)
            emit_json
            ;;
        line)
            if command -v jq >/dev/null 2>&1; then
                emit_line_with_jq
            else
                emit_line_without_jq
            fi
            ;;
        tsv)
            if command -v jq >/dev/null 2>&1; then
                emit_tsv_with_jq
            else
                emit_tsv_without_jq
            fi
            ;;
        *)
            echo "[metrics-reporter] unsupported format: ${FORMAT}" >&2
            exit 1
            ;;
    esac
}

while true; do
    emit_once
    if (( WATCH_SEC <= 0 )); then
        break
    fi
    sleep "${WATCH_SEC}"
done

exit 0