#!/usr/bin/env bash
# =============================================================================
# Cinder Control — metrics-aggregate.sh
# Pull latest metrics from one or more compute nodes over SSH and aggregate.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${REMOTE_LATEST:=/opt/cinder/metrics/latest.json}"
: "${OUTPUT_FILE:=${CINDER_BASE_DIR}/metrics/aggregate-latest.json}"

declare -a NODES=()
SSH_OPTS="-o BatchMode=yes -o ConnectTimeout=5"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --node)
            NODES+=("${2:?--node requires a host}")
            shift 2
            ;;
        --remote-latest)
            REMOTE_LATEST="${2:?--remote-latest requires a value}"
            shift 2
            ;;
        --output)
            OUTPUT_FILE="${2:?--output requires a value}"
            shift 2
            ;;
        --ssh-opts)
            SSH_OPTS="${2:?--ssh-opts requires a value}"
            shift 2
            ;;
        -h|--help)
            sed -n '1,14p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "[metrics-aggregate] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if (( ${#NODES[@]} == 0 )); then
    echo "[metrics-aggregate] at least one --node is required" >&2
    exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
    echo "[metrics-aggregate] jq is required" >&2
    exit 1
fi

mkdir -p "$(dirname "${OUTPUT_FILE}")"

TMP_NODES="$(mktemp)"
trap 'rm -f "${TMP_NODES}"' EXIT

printf '[]' > "${TMP_NODES}"

for node in "${NODES[@]}"; do
    raw="$(ssh ${SSH_OPTS} "${node}" "cat '${REMOTE_LATEST}'" 2>/dev/null || true)"

    if [[ -z "${raw}" ]]; then
        printf '[metrics-aggregate] WARN node=%s unreachable or no data\n' "${node}" >&2
        printf '%s' "$(jq --arg node "${node}" '. += [{node:$node, alive:false, players:0, tps:0, mspt:0}]' "${TMP_NODES}")" > "${TMP_NODES}"
        continue
    fi

    node_json="$(printf '%s\n' "${raw}" | jq -c --arg node "${node}" '{node:$node, alive:(.server.alive // false), players:(.server.players // 0), tps:(.runtime.tps // 0), mspt:(.runtime.mspt // 0)}' 2>/dev/null || true)"
    if [[ -z "${node_json}" ]]; then
        printf '[metrics-aggregate] WARN node=%s invalid JSON\n' "${node}" >&2
        printf '%s' "$(jq --arg node "${node}" '. += [{node:$node, alive:false, players:0, tps:0, mspt:0}]' "${TMP_NODES}")" > "${TMP_NODES}"
        continue
    fi

    printf '%s' "$(jq --argjson nodeEntry "${node_json}" '. += [$nodeEntry]' "${TMP_NODES}")" > "${TMP_NODES}"
done

AGG_JSON="$(jq -n --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --slurpfile nodes "${TMP_NODES}" '
    ($nodes[0]) as $n |
    {
      timestamp: $ts,
      nodes: $n,
      totals: {
        totalPlayers: ($n | map(.players) | add),
        avgTps: (if ($n|length)==0 then 0 else (($n | map(.tps) | add) / ($n|length)) end),
        worstMspt: (if ($n|length)==0 then 0 else ($n | map(.mspt) | max) end),
        aliveNodes: ($n | map(select(.alive == true)) | length)
      }
    }
')"

printf '%s\n' "${AGG_JSON}" > "${OUTPUT_FILE}"
printf '[metrics-aggregate] wrote %s\n' "${OUTPUT_FILE}"
