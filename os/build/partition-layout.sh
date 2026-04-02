#!/usr/bin/env bash
# =============================================================================
# Cinder OS - partition-layout.sh
# Deterministic MBR partition layout for Cinder OS ARM64 images/devices.
#
# Layout:
#   p1  FAT32  256MiB   /boot/firmware
#   p2  ext4   4GiB     /
#   p3  ext4   rest      /data
#
# Usage:
#   ./partition-layout.sh --target <image-file-or-block-device>
#                         [--boot-size-mib 256]
#                         [--root-size-gib 4]
#                         [--align-mib 4]
#                         [--dry-run]
#
# Notes:
#   - Non-interactive and CI-safe.
#   - Writes an msdos (MBR) partition table.
#   - Prints computed geometry for downstream scripts.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BOOT_SIZE_MIB:=256}"
: "${CINDER_ROOT_SIZE_GIB:=4}"
: "${CINDER_ALIGN_MIB:=4}"

TARGET_PATH=""
DRY_RUN=false

_log()  { echo "[partition] $(date -u +%H:%M:%SZ)  $*"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() { _log "FAIL    $*" >&2; exit 1; }

_usage() {
    cat <<'EOF'
Usage:
  partition-layout.sh --target <image-file-or-block-device>
                      [--boot-size-mib <int>]
                      [--root-size-gib <int>]
                      [--align-mib <int>]
                      [--dry-run]
EOF
}

_require_cmd() {
    command -v "$1" >/dev/null 2>&1 || _fail "Required command not found: $1"
}

_is_int() {
    [[ "$1" =~ ^[0-9]+$ ]]
}

_target_size_bytes() {
    local target="$1"
    if [[ -b "${target}" ]]; then
        blockdev --getsize64 "${target}"
    elif [[ -f "${target}" ]]; then
        stat -c%s "${target}"
    else
        _fail "Target is neither a block device nor a regular file: ${target}"
    fi
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)
            TARGET_PATH="${2:?--target requires a path}"
            shift 2
            ;;
        --boot-size-mib)
            CINDER_BOOT_SIZE_MIB="${2:?--boot-size-mib requires a value}"
            shift 2
            ;;
        --root-size-gib)
            CINDER_ROOT_SIZE_GIB="${2:?--root-size-gib requires a value}"
            shift 2
            ;;
        --align-mib)
            CINDER_ALIGN_MIB="${2:?--align-mib requires a value}"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            _usage
            exit 0
            ;;
        *)
            _fail "Unknown argument: $1"
            ;;
    esac
done

[[ -n "${TARGET_PATH}" ]] || _fail "--target is required"
[[ -e "${TARGET_PATH}" ]] || _fail "Target path does not exist: ${TARGET_PATH}"

_is_int "${CINDER_BOOT_SIZE_MIB}" || _fail "--boot-size-mib must be an integer"
_is_int "${CINDER_ROOT_SIZE_GIB}" || _fail "--root-size-gib must be an integer"
_is_int "${CINDER_ALIGN_MIB}" || _fail "--align-mib must be an integer"

(( CINDER_BOOT_SIZE_MIB >= 64 )) || _fail "--boot-size-mib must be >= 64"
(( CINDER_ROOT_SIZE_GIB >= 2 )) || _fail "--root-size-gib must be >= 2"
(( CINDER_ALIGN_MIB >= 1 )) || _fail "--align-mib must be >= 1"

_require_cmd parted
_require_cmd blockdev
_require_cmd stat

ROOT_SIZE_MIB=$(( CINDER_ROOT_SIZE_GIB * 1024 ))
BOOT_START_MIB="${CINDER_ALIGN_MIB}"
BOOT_END_MIB=$(( BOOT_START_MIB + CINDER_BOOT_SIZE_MIB ))
ROOT_START_MIB="${BOOT_END_MIB}"
ROOT_END_MIB=$(( ROOT_START_MIB + ROOT_SIZE_MIB ))
DATA_START_MIB="${ROOT_END_MIB}"

TARGET_BYTES="$(_target_size_bytes "${TARGET_PATH}")"
TOTAL_MIB=$(( TARGET_BYTES / 1024 / 1024 ))

if (( TOTAL_MIB <= DATA_START_MIB + 16 )); then
    _fail "Target is too small for requested layout (size=${TOTAL_MIB}MiB, required>$((${DATA_START_MIB} + 16))MiB)"
fi

_log "Target: ${TARGET_PATH}"
_log "Layout: p1=fat32 ${CINDER_BOOT_SIZE_MIB}MiB, p2=ext4 ${CINDER_ROOT_SIZE_GIB}GiB, p3=ext4 remainder"
_log "Geometry: p1 ${BOOT_START_MIB}-${BOOT_END_MIB}MiB, p2 ${ROOT_START_MIB}-${ROOT_END_MIB}MiB, p3 ${DATA_START_MIB}MiB-100%"

if [[ "${DRY_RUN}" == true ]]; then
    _warn "Dry-run enabled - no partition table changes written"
else
    parted --script --align optimal "${TARGET_PATH}" \
        mklabel msdos \
        mkpart primary fat32 "${BOOT_START_MIB}MiB" "${BOOT_END_MIB}MiB" \
        mkpart primary ext4 "${ROOT_START_MIB}MiB" "${ROOT_END_MIB}MiB" \
        mkpart primary ext4 "${DATA_START_MIB}MiB" "100%" \
        set 1 boot on

    if [[ -b "${TARGET_PATH}" ]]; then
        partprobe "${TARGET_PATH}" || true
    fi

    _pass "Partition table written successfully"
fi

cat <<EOF
BOOT_START_MIB=${BOOT_START_MIB}
BOOT_END_MIB=${BOOT_END_MIB}
ROOT_START_MIB=${ROOT_START_MIB}
ROOT_END_MIB=${ROOT_END_MIB}
DATA_START_MIB=${DATA_START_MIB}
EOF
