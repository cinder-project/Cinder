#!/usr/bin/env bash
# Cinder Bench — chunk load/unload stress benchmark wrapper.

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PLAYER_PATH_PATTERN="spiral"
SIM_PLAYERS=10

declare -a PASSTHROUGH=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pattern)
            PLAYER_PATH_PATTERN="${2:?--pattern requires a value}"
            shift 2
            ;;
        --players)
            SIM_PLAYERS="${2:?--players requires a value}"
            shift 2
            ;;
        *)
            PASSTHROUGH+=("$1")
            shift
            ;;
    esac
done

exec "${SCRIPT_DIR}/run-load-benchmark.sh" \
    --scenario "chunk-stress" \
    --meta "pattern=${PLAYER_PATH_PATTERN}" \
    --meta "simPlayers=${SIM_PLAYERS}" \
    "${PASSTHROUGH[@]}"
