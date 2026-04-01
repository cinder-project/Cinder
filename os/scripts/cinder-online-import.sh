#!/usr/bin/env bash
# Download mods/plugins/packs from URL into Cinder directories.

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${TARGET_MODS_DIR:=${CINDER_BASE_DIR}/mods}"
: "${TARGET_PLUGINS_DIR:=${CINDER_BASE_DIR}/plugins}"
: "${TARGET_DATAPACKS_DIR:=${CINDER_BASE_DIR}/world/datapacks}"
: "${TARGET_RESOURCEPACKS_DIR:=${CINDER_BASE_DIR}/resourcepacks}"

URL=""
TARGET=""
OUTPUT_NAME=""
INTERACTIVE=false
DRY_RUN=false

usage() {
    cat <<'EOF'
Usage:
  cinder-online-import.sh --url <https://...> --target <mods|plugins|datapacks|resourcepacks> [--name <file>]
  cinder-online-import.sh --interactive
  cinder-online-import.sh --dry-run --url <https://...> --target <mods|plugins|datapacks|resourcepacks>

Examples:
  cinder-online-import.sh --url "https://example.com/mod.jar" --target mods
  cinder-online-import.sh --interactive
EOF
}

log()  { echo "[online-import] $*"; }
warn() { echo "[online-import:WARN] $*" >&2; }
fatal() { echo "[online-import:FATAL] $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --url)
            URL="${2:-}"
            shift 2
            ;;
        --target)
            TARGET="${2:-}"
            shift 2
            ;;
        --name)
            OUTPUT_NAME="${2:-}"
            shift 2
            ;;
        --interactive)
            INTERACTIVE=true
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fatal "Unknown argument: $1"
            ;;
    esac
done

if [[ "${INTERACTIVE}" == true ]]; then
    if command -v zenity >/dev/null 2>&1; then
        URL="$(zenity --entry --title='Cinder Online Import' --text='Enter direct mod/plugin/pack URL:' --width=720 || true)"
        [[ -z "${URL}" ]] && exit 0

        TARGET="$(zenity --list --title='Cinder Online Import' --text='Select destination:' --column='Target' mods plugins datapacks resourcepacks --height=280 --width=360 || true)"
        [[ -z "${TARGET}" ]] && exit 0

        OUTPUT_NAME="$(zenity --entry --title='Cinder Online Import' --text='Optional custom filename (leave blank to auto-detect):' --width=720 || true)"
    else
        echo "No zenity detected. Falling back to terminal prompts."
        read -r -p "URL: " URL
        read -r -p "Target (mods/plugins/datapacks/resourcepacks): " TARGET
        read -r -p "Optional output filename: " OUTPUT_NAME
    fi
fi

[[ -z "${URL}" ]] && fatal "--url is required"
[[ -z "${TARGET}" ]] && fatal "--target is required"

if [[ ! "${URL}" =~ ^https?:// ]]; then
    fatal "Only http/https URLs are supported"
fi

case "${TARGET}" in
    mods)
        DEST_DIR="${TARGET_MODS_DIR}"
        REQUIRED_EXTENSIONS="jar"
        ;;
    plugins)
        DEST_DIR="${TARGET_PLUGINS_DIR}"
        REQUIRED_EXTENSIONS="jar"
        ;;
    datapacks)
        DEST_DIR="${TARGET_DATAPACKS_DIR}"
        REQUIRED_EXTENSIONS="zip jar"
        ;;
    resourcepacks)
        DEST_DIR="${TARGET_RESOURCEPACKS_DIR}"
        REQUIRED_EXTENSIONS="zip"
        ;;
    *)
        fatal "Invalid target: ${TARGET}"
        ;;
esac

if [[ -z "${OUTPUT_NAME}" ]]; then
    stripped_url="${URL%%\?*}"
    OUTPUT_NAME="$(basename "${stripped_url}")"
fi

if [[ -z "${OUTPUT_NAME}" || "${OUTPUT_NAME}" == "/" || "${OUTPUT_NAME}" == "." ]]; then
    OUTPUT_NAME="import-$(date -u +%Y%m%dT%H%M%SZ).bin"
fi

EXTENSION="${OUTPUT_NAME##*.}"
EXTENSION="${EXTENSION,,}"

ext_ok=false
for allowed in ${REQUIRED_EXTENSIONS}; do
    if [[ "${EXTENSION}" == "${allowed}" ]]; then
        ext_ok=true
        break
    fi
done

if [[ "${ext_ok}" != true ]]; then
    fatal "File extension .${EXTENSION} is not allowed for target '${TARGET}' (allowed: ${REQUIRED_EXTENSIONS})"
fi

mkdir -p "${DEST_DIR}"

TMP_FILE="$(mktemp /tmp/cinder-online-import.XXXXXX)"
cleanup() {
    rm -f "${TMP_FILE}" 2>/dev/null || true
}
trap cleanup EXIT

if command -v curl >/dev/null 2>&1; then
    log "Downloading via curl..."
    curl -fL --retry 4 --retry-delay 2 --connect-timeout 20 --max-time 600 -o "${TMP_FILE}" "${URL}"
elif command -v wget >/dev/null 2>&1; then
    log "Downloading via wget..."
    wget -O "${TMP_FILE}" "${URL}"
else
    fatal "Neither curl nor wget is available"
fi

DEST_FILE="${DEST_DIR}/${OUTPUT_NAME}"

if [[ "${DRY_RUN}" == true ]]; then
    log "Dry-run successful."
    log "Would install: ${URL} -> ${DEST_FILE}"
    exit 0
fi

install -m 0644 "${TMP_FILE}" "${DEST_FILE}"
chown cinder:cinder "${DEST_FILE}" 2>/dev/null || true

SHA256="$(sha256sum "${DEST_FILE}" | awk '{print $1}')"
log "Installed: ${DEST_FILE}"
log "SHA256: ${SHA256}"

if [[ "${INTERACTIVE}" == true ]] && command -v zenity >/dev/null 2>&1; then
    zenity --info --title='Cinder Online Import' --text="Imported to:\n${DEST_FILE}\n\nSHA256:\n${SHA256}" --width=640 || true
fi
