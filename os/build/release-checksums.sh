#!/usr/bin/env bash
# Generate SHA-256 checksums for release artifacts.

set -euo pipefail
IFS=$'\n\t'

ARTIFACT_DIR="${1:-./build/libs}"
OUTPUT_FILE="${2:-${ARTIFACT_DIR}/SHA256SUMS}"

if [[ ! -d "${ARTIFACT_DIR}" ]]; then
    echo "[release-checksums] artifact directory not found: ${ARTIFACT_DIR}" >&2
    exit 1
fi

mapfile -t FILES < <(
    find "${ARTIFACT_DIR}" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.img' -o -name '*.img.xz' -o -name '*.img.zst' -o -name '*.iso' \) | sort
)

if [[ ${#FILES[@]} -eq 0 ]]; then
    : > "${OUTPUT_FILE}"
    echo "[release-checksums] no matching artifacts found in ${ARTIFACT_DIR}; wrote empty ${OUTPUT_FILE}"
    exit 0
fi

sha256sum "${FILES[@]}" > "${OUTPUT_FILE}"

echo "[release-checksums] wrote ${OUTPUT_FILE}"
