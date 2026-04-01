#!/usr/bin/env bash
# =============================================================================
# Cinder Bench — startup-time.sh
# Measure startup latency from launch log to first tick completion log.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

LAUNCH_LOG=""
SERVER_LOG=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --launch-log)
            LAUNCH_LOG="${2:?--launch-log requires a value}"
            shift 2
            ;;
        --server-log)
            SERVER_LOG="${2:?--server-log requires a value}"
            shift 2
            ;;
        -h|--help)
            echo "Usage: startup-time.sh --launch-log <file> --server-log <file>"
            exit 0
            ;;
        *)
            echo "[startup-time] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ -z "${LAUNCH_LOG}" || -z "${SERVER_LOG}" ]]; then
    echo "[startup-time] both --launch-log and --server-log are required" >&2
    exit 1
fi

if [[ ! -f "${LAUNCH_LOG}" || ! -f "${SERVER_LOG}" ]]; then
    echo "[startup-time] log files not found" >&2
    exit 1
fi

START_HMS="$(grep -m1 'Launching Cinder Core' "${LAUNCH_LOG}" | sed -n 's/^\[\([0-9][0-9]:[0-9][0-9]:[0-9][0-9]\)\].*/\1/p')"
END_HMS="$(grep -m1 'First tick completed' "${SERVER_LOG}" | sed -n 's/^\[\([0-9][0-9]:[0-9][0-9]:[0-9][0-9]\)\].*/\1/p')"

if [[ -z "${START_HMS}" || -z "${END_HMS}" ]]; then
    echo "[startup-time] could not find required markers in logs" >&2
    exit 1
fi

start_s=$((10#${START_HMS:0:2} * 3600 + 10#${START_HMS:3:2} * 60 + 10#${START_HMS:6:2}))
end_s=$((10#${END_HMS:0:2} * 3600 + 10#${END_HMS:3:2} * 60 + 10#${END_HMS:6:2}))

if (( end_s < start_s )); then
    end_s=$(( end_s + 86400 ))
fi

elapsed=$(( end_s - start_s ))

cat <<EOF
Startup Time Analysis
=====================
Launch marker : ${START_HMS}
First tick    : ${END_HMS}
Elapsed       : ${elapsed}s
EOF
