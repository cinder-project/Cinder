#!/usr/bin/env bash
# =============================================================================
# Cinder OS — create-release-iso.sh
# Package image artifacts into a distributable ISO bundle.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${OUTPUT_DIR:=./os/build/output}"
: "${VERSION:=}"
: "${BUILD_DATE:=}"
: "${ISO_LABEL:=CINDER_OS}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir)
            OUTPUT_DIR="${2:?--output-dir requires a value}"
            shift 2
            ;;
        --version)
            VERSION="${2:?--version requires a value}"
            shift 2
            ;;
        --build-date)
            BUILD_DATE="${2:?--build-date requires a value}"
            shift 2
            ;;
        --iso-label)
            ISO_LABEL="${2:?--iso-label requires a value}"
            shift 2
            ;;
        -h|--help)
            sed -n '1,16p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "[create-release-iso] Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ -z "${VERSION}" ]]; then
    echo "[create-release-iso] --version is required" >&2
    exit 1
fi

if [[ -z "${BUILD_DATE}" ]]; then
    BUILD_DATE="$(date -u +%Y%m%d)"
fi

if ! command -v xorriso >/dev/null 2>&1; then
    echo "[create-release-iso] xorriso is required (apt install xorriso)" >&2
    exit 1
fi

if [[ ! -d "${OUTPUT_DIR}" ]]; then
    echo "[create-release-iso] output dir not found: ${OUTPUT_DIR}" >&2
    exit 1
fi

IMAGE_BASENAME="cinder-os-${VERSION}-arm64"
LEGACY_IMAGE_BASENAME="cinder-os-${VERSION}-${BUILD_DATE}"
ISO_NAME="${IMAGE_BASENAME}-bundle.iso"
ISO_PATH="${OUTPUT_DIR}/${ISO_NAME}"
STAGE_DIR="${OUTPUT_DIR}/iso-staging"

mkdir -p "${STAGE_DIR}"

copy_if_exists() {
    local src="$1"
    if [[ -f "${src}" ]]; then
        cp "${src}" "${STAGE_DIR}/"
    fi
}

copy_if_exists "${OUTPUT_DIR}/${IMAGE_BASENAME}.img.zst"
copy_if_exists "${OUTPUT_DIR}/${IMAGE_BASENAME}.img.zst.sha256"
copy_if_exists "${OUTPUT_DIR}/${IMAGE_BASENAME}.img.xz"
copy_if_exists "${OUTPUT_DIR}/${IMAGE_BASENAME}.img.xz.sha256"
copy_if_exists "${OUTPUT_DIR}/${IMAGE_BASENAME}.img.sha256"

copy_if_exists "${OUTPUT_DIR}/${LEGACY_IMAGE_BASENAME}.img.zst"
copy_if_exists "${OUTPUT_DIR}/${LEGACY_IMAGE_BASENAME}.img.zst.sha256"
copy_if_exists "${OUTPUT_DIR}/${LEGACY_IMAGE_BASENAME}.img.xz"
copy_if_exists "${OUTPUT_DIR}/${LEGACY_IMAGE_BASENAME}.img.xz.sha256"
copy_if_exists "${OUTPUT_DIR}/${LEGACY_IMAGE_BASENAME}.img.sha256"
copy_if_exists "${OUTPUT_DIR}/SHA256SUMS"

if [[ -z "$(find "${STAGE_DIR}" -maxdepth 1 -type f -print -quit)" ]]; then
    echo "[create-release-iso] no artifact files found to bundle in ${STAGE_DIR}" >&2
    exit 1
fi

cat > "${STAGE_DIR}/README.txt" <<EOF
Cinder OS Release Bundle
========================

Version: ${VERSION}
Build Date: ${BUILD_DATE}

This ISO contains compressed image artifacts and checksums.
Preferred format is .img.zst; .img.xz is included when available for compatibility.
EOF

xorriso -as mkisofs \
    -V "${ISO_LABEL}" \
    -o "${ISO_PATH}" \
    "${STAGE_DIR}" >/dev/null

echo "[create-release-iso] created ${ISO_PATH}"
