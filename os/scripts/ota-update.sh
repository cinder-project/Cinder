#!/usr/bin/env bash
# =============================================================================
# Cinder OS — ota-update.sh
# In-place minor OTA update path for Cinder runtime artifacts.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_RELEASE_URL:=}"
: "${CINDER_TMP_DIR:=/tmp/cinder-ota}"

if [[ -z "${CINDER_RELEASE_URL}" ]]; then
    echo "[ota-update] CINDER_RELEASE_URL is required" >&2
    exit 1
fi

mkdir -p "${CINDER_TMP_DIR}"
ARCHIVE="${CINDER_TMP_DIR}/release.tar.gz"

if ! command -v curl >/dev/null 2>&1; then
    echo "[ota-update] curl is required" >&2
    exit 1
fi

echo "[ota-update] downloading release archive..."
curl -fsSL "${CINDER_RELEASE_URL}" -o "${ARCHIVE}"

echo "[ota-update] extracting archive..."
mkdir -p "${CINDER_TMP_DIR}/extract"
tar -xzf "${ARCHIVE}" -C "${CINDER_TMP_DIR}/extract"

if [[ -f "${CINDER_TMP_DIR}/extract/update.sh" ]]; then
    echo "[ota-update] delegating to extracted update.sh"
    bash "${CINDER_TMP_DIR}/extract/update.sh"
else
    echo "[ota-update] no update.sh in archive; applying overlay copy"
    rsync -a --delete "${CINDER_TMP_DIR}/extract/" "${CINDER_BASE_DIR}/"
fi

echo "[ota-update] update complete"
