#!/usr/bin/env bash
# Cinder Bench — connection stress benchmark wrapper.

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SIM_CONNECTIONS=20
MOVE_PROFILE="default"

declare -a PASSTHROUGH=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --connections)
            SIM_CONNECTIONS="${2:?--connections requires a value}"
            shift 2
            ;;
        --move-profile)
            MOVE_PROFILE="${2:?--move-profile requires a value}"
            shift 2
            ;;
        *)
            PASSTHROUGH+=("$1")
            shift
            ;;
    esac
done

exec "${SCRIPT_DIR}/run-load-benchmark.sh" \
    --scenario "connection-stress" \
    --meta "connections=${SIM_CONNECTIONS}" \
    --meta "moveProfile=${MOVE_PROFILE}" \
    "${PASSTHROUGH[@]}"
