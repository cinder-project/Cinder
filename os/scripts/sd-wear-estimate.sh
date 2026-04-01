#!/usr/bin/env bash
# =============================================================================
# Cinder OS — sd-wear-estimate.sh
# Estimate storage wear from SMART (if available) and report health hints.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

DEVICE="${1:-}"

if [[ -z "${DEVICE}" ]]; then
    DEVICE="$(findmnt -n -o SOURCE / | head -n1 || true)"
fi

if [[ -z "${DEVICE}" ]]; then
    echo "[sd-wear-estimate] unable to resolve root device" >&2
    exit 1
fi

echo "[sd-wear-estimate] device=${DEVICE}"

if command -v smartctl >/dev/null 2>&1; then
    SMART="$(smartctl -a "${DEVICE}" 2>/dev/null || true)"
    if [[ -n "${SMART}" ]]; then
        echo "[sd-wear-estimate] SMART summary"
        echo "${SMART}" | grep -Ei 'wear|life|percent|temperature|power_on_hours|media_wearout' || true
        exit 0
    fi
fi

if [[ "${DEVICE}" == *mmcblk* ]]; then
    echo "[sd-wear-estimate] SMART unavailable for MMC device; use periodic backup and replacement schedule."
    echo "[sd-wear-estimate] hint: monitor dmesg for I/O errors and check /sys/block/mmcblk*/stat trends."
    exit 0
fi

echo "[sd-wear-estimate] no SMART data available for ${DEVICE}."
exit 0
