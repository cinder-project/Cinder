#!/usr/bin/env bash
# =============================================================================
# Cinder Bench — phase-analysis.sh
# Extract per-phase MSPT means and identify the dominant phase.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

INPUT_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --input)
            INPUT_FILE="${2:?--input requires a value}"
            shift 2
            ;;
        -h|--help)
            echo "Usage: phase-analysis.sh --input <tick-log>"
            exit 0
            ;;
        *)
            echo "[phase-analysis] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ -z "${INPUT_FILE}" || ! -f "${INPUT_FILE}" ]]; then
    echo "[phase-analysis] valid --input file is required" >&2
    exit 1
fi

awk '
BEGIN {
    pre=0; world=0; entity=0; chunk=0; post=0; n=0;
}
/total=.*ms/ {
    if (match($0, /PRE=([0-9.]+)/, a)) pre += a[1];
    if (match($0, /WORLD=([0-9.]+)/, b)) world += b[1];
    if (match($0, /ENTITY=([0-9.]+)/, c)) entity += c[1];
    if (match($0, /CHUNK=([0-9.]+)/, d)) chunk += d[1];
    if (match($0, /POST=([0-9.]+)/, e)) post += e[1];
    n++;
}
END {
    if (n == 0) {
        print "No phase lines found.";
        exit 1;
    }

    preAvg = pre / n;
    worldAvg = world / n;
    entityAvg = entity / n;
    chunkAvg = chunk / n;
    postAvg = post / n;

    dominant = "PRE";
    dominantVal = preAvg;

    if (worldAvg > dominantVal) { dominant = "WORLD"; dominantVal = worldAvg; }
    if (entityAvg > dominantVal) { dominant = "ENTITY"; dominantVal = entityAvg; }
    if (chunkAvg > dominantVal) { dominant = "CHUNK"; dominantVal = chunkAvg; }
    if (postAvg > dominantVal) { dominant = "POST"; dominantVal = postAvg; }

    printf "Phase Analysis\n";
    printf "==============\n";
    printf "Samples: %d\n", n;
    printf "PRE avg   : %.3f ms\n", preAvg;
    printf "WORLD avg : %.3f ms\n", worldAvg;
    printf "ENTITY avg: %.3f ms\n", entityAvg;
    printf "CHUNK avg : %.3f ms\n", chunkAvg;
    printf "POST avg  : %.3f ms\n", postAvg;
    printf "Dominant phase: %s (%.3f ms)\n", dominant, dominantVal;
}
' "${INPUT_FILE}"
