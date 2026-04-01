#!/usr/bin/env bash
# Cinder Bench — entity stress benchmark wrapper.

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ENTITY_COUNT=200
ENTITY_TIER="STANDARD"

declare -a PASSTHROUGH=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --entities)
            ENTITY_COUNT="${2:?--entities requires a value}"
            shift 2
            ;;
        --tier)
            ENTITY_TIER="${2:?--tier requires a value}"
            shift 2
            ;;
        *)
            PASSTHROUGH+=("$1")
            shift
            ;;
    esac
done

exec "${SCRIPT_DIR}/run-load-benchmark.sh" \
    --scenario "entity-stress" \
    --meta "entities=${ENTITY_COUNT}" \
    --meta "tier=${ENTITY_TIER}" \
    "${PASSTHROUGH[@]}"
