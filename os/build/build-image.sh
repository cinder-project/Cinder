#!/usr/bin/env bash
# =============================================================================
# Cinder OS - build-image.sh
# Full-image build orchestrator for Debian 12 ARM64 Cinder OS images.
#
# Output:
#   cinder-os-<version>-arm64.img.zst
#   cinder-os-<version>-arm64.img.zst.sha256
#
# Layout (MBR):
#   p1  FAT32  256MiB   /boot/firmware
#   p2  ext4   4GiB     /
#   p3  ext4   remainder /data
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

: "${CINDER_OS_VERSION:=1.0.0}"
: "${IMAGE_SIZE_GIB:=8}"
: "${OUTPUT_DIR:=${SCRIPT_DIR}/output}"
: "${WORK_DIR:=${SCRIPT_DIR}/work}"
: "${DEBIAN_RELEASE:=bookworm}"
: "${DEBIAN_MIRROR:=https://deb.debian.org/debian}"
: "${RPI_MIRROR:=http://archive.raspberrypi.com/debian}"
: "${PAPER_MC_VERSION:=1.20.1}"
: "${PAPER_BUILD:=latest}"
: "${CINDER_OS_PROFILE:=server}"
: "${KEEP_RAW_IMAGE:=false}"
: "${KEEP_WORK_DIR:=false}"

readonly PARTITION_LAYOUT_SCRIPT="${SCRIPT_DIR}/partition-layout.sh"
readonly CHROOT_SETUP_SCRIPT="${SCRIPT_DIR}/chroot-setup.sh"

LOG_FILE=""
LOOP_DEV=""
MOUNTED_DIRS=()

_log()  { echo "[build-image] $(date -u +%H:%M:%SZ)  $*"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() { _log "FAIL    $*" >&2; cleanup; exit 1; }

_usage() {
    cat <<'EOF'
Usage:
  build-image.sh [options]

Options:
  --version <ver>            Version string in output artifact name
  --image-size-gib <int>     Raw image size in GiB (default: 8)
    --image-size <int>         Legacy alias for --image-size-gib
    --profile <name>           Legacy profile argument (accepted for compatibility)
  --output-dir <path>        Output artifact directory
  --work-dir <path>          Build workspace directory
  --debian-release <name>    Debian release (default: bookworm)
  --debian-mirror <url>      Debian mirror URL
  --rpi-mirror <url>         Raspberry Pi archive URL
    --paper-mc-version <v>     PaperMC target Minecraft version (default: 1.20.1)
    --paper-build <n|latest>   PaperMC build number or latest (default: latest)
  --keep-raw-image           Keep .img after zstd compression
  --keep-work-dir            Keep mounted work directory after build
  --log-file <path>          Build log file path
  -h, --help                 Show help
EOF
}

_require_cmd() {
    command -v "$1" >/dev/null 2>&1 || _fail "Required command not found: $1"
}

_cleanup_mount() {
    local mount_point="$1"
    if mountpoint -q "${mount_point}"; then
        umount "${mount_point}" 2>/dev/null || true
    fi
}

cleanup() {
    local idx
    for (( idx=${#MOUNTED_DIRS[@]} - 1; idx>=0; idx-- )); do
        _cleanup_mount "${MOUNTED_DIRS[idx]}"
    done
    MOUNTED_DIRS=()

    if [[ -n "${LOOP_DEV}" ]]; then
        losetup -d "${LOOP_DEV}" 2>/dev/null || true
        LOOP_DEV=""
    fi
}

trap cleanup EXIT

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)
            CINDER_OS_VERSION="${2:?--version requires a value}"
            shift 2
            ;;
        --image-size-gib|--image-size)
            IMAGE_SIZE_GIB="${2:?--image-size-gib requires a value}"
            shift 2
            ;;
        --profile)
            CINDER_OS_PROFILE="${2:?--profile requires a value}"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="${2:?--output-dir requires a value}"
            shift 2
            ;;
        --work-dir)
            WORK_DIR="${2:?--work-dir requires a value}"
            shift 2
            ;;
        --debian-release)
            DEBIAN_RELEASE="${2:?--debian-release requires a value}"
            shift 2
            ;;
        --debian-mirror)
            DEBIAN_MIRROR="${2:?--debian-mirror requires a value}"
            shift 2
            ;;
        --rpi-mirror)
            RPI_MIRROR="${2:?--rpi-mirror requires a value}"
            shift 2
            ;;
        --paper-mc-version)
            PAPER_MC_VERSION="${2:?--paper-mc-version requires a value}"
            shift 2
            ;;
        --paper-build)
            PAPER_BUILD="${2:?--paper-build requires a value}"
            shift 2
            ;;
        --keep-raw-image)
            KEEP_RAW_IMAGE=true
            shift
            ;;
        --keep-work-dir)
            KEEP_WORK_DIR=true
            shift
            ;;
        --log-file)
            LOG_FILE="${2:?--log-file requires a value}"
            shift 2
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

[[ "${EUID}" -eq 0 ]] || _fail "Must run as root"
[[ "${IMAGE_SIZE_GIB}" =~ ^[0-9]+$ ]] || _fail "--image-size-gib must be an integer"
(( IMAGE_SIZE_GIB >= 8 )) || _fail "--image-size-gib must be >= 8"

case "${CINDER_OS_PROFILE}" in
    server)
        ;;
    desktop)
        _warn "Desktop profile flag accepted for compatibility; build-image now produces headless server images only"
        ;;
    *)
        _fail "Unsupported profile '${CINDER_OS_PROFILE}' (expected: server|desktop)"
        ;;
esac

[[ -f "${PARTITION_LAYOUT_SCRIPT}" ]] || _fail "Missing script: ${PARTITION_LAYOUT_SCRIPT}"
[[ -f "${CHROOT_SETUP_SCRIPT}" ]] || _fail "Missing script: ${CHROOT_SETUP_SCRIPT}"

_require_cmd losetup
_require_cmd parted
_require_cmd partprobe
_require_cmd mkfs.vfat
_require_cmd mkfs.ext4
_require_cmd blkid
_require_cmd mount
_require_cmd umount
_require_cmd zstd
_require_cmd sha256sum
_require_cmd sync
_require_cmd truncate

mkdir -p "${OUTPUT_DIR}" "${WORK_DIR}"

if [[ -z "${LOG_FILE}" ]]; then
    LOG_FILE="${OUTPUT_DIR}/build-image-${CINDER_OS_VERSION}.log"
fi
mkdir -p "$(dirname "${LOG_FILE}")"
exec > >(tee -a "${LOG_FILE}") 2>&1

readonly IMAGE_BASENAME="cinder-os-${CINDER_OS_VERSION}-arm64"
readonly IMAGE_FILE="${OUTPUT_DIR}/${IMAGE_BASENAME}.img"
readonly IMAGE_ZST_FILE="${IMAGE_FILE}.zst"
readonly IMAGE_ZST_SHA256_FILE="${IMAGE_ZST_FILE}.sha256"
readonly ROOTFS_DIR="${WORK_DIR}/rootfs"

_log "Cinder OS build start"
_log "version=${CINDER_OS_VERSION} image-size=${IMAGE_SIZE_GIB}GiB"
_log "output=${IMAGE_ZST_FILE}"

_step() {
    echo
    _log "============================================"
    _log "$*"
    _log "============================================"
}

_step "Create raw image"
rm -f "${IMAGE_FILE}" "${IMAGE_ZST_FILE}" "${IMAGE_ZST_SHA256_FILE}"
truncate -s "$(( IMAGE_SIZE_GIB * 1024 * 1024 * 1024 ))" "${IMAGE_FILE}"
_pass "Raw image allocated: ${IMAGE_FILE}"

_step "Partition image"
bash "${PARTITION_LAYOUT_SCRIPT}" --target "${IMAGE_FILE}" --boot-size-mib 256 --root-size-gib 4 --align-mib 4
_pass "Partition layout applied"

_step "Attach loop device and format partitions"
LOOP_DEV="$(losetup --find --show --partscan "${IMAGE_FILE}")"

BOOT_PART="${LOOP_DEV}p1"
ROOT_PART="${LOOP_DEV}p2"
DATA_PART="${LOOP_DEV}p3"

for _ in {1..20}; do
    if [[ -b "${BOOT_PART}" && -b "${ROOT_PART}" && -b "${DATA_PART}" ]]; then
        break
    fi
    sleep 0.2
done

[[ -b "${BOOT_PART}" ]] || _fail "Boot partition not detected: ${BOOT_PART}"
[[ -b "${ROOT_PART}" ]] || _fail "Root partition not detected: ${ROOT_PART}"
[[ -b "${DATA_PART}" ]] || _fail "Data partition not detected: ${DATA_PART}"

mkfs.vfat -F 32 -n CINDER_BOOT "${BOOT_PART}"
mkfs.ext4 -F -L CINDER_ROOT "${ROOT_PART}"
mkfs.ext4 -F -L CINDER_DATA "${DATA_PART}"
_pass "Filesystem formatting complete"

BOOT_UUID="$(blkid -s UUID -o value "${BOOT_PART}")"
ROOT_UUID="$(blkid -s UUID -o value "${ROOT_PART}")"
DATA_UUID="$(blkid -s UUID -o value "${DATA_PART}")"

_step "Mount rootfs"
rm -rf "${ROOTFS_DIR}"
mkdir -p "${ROOTFS_DIR}"

mount "${ROOT_PART}" "${ROOTFS_DIR}"
MOUNTED_DIRS+=("${ROOTFS_DIR}")

mkdir -p "${ROOTFS_DIR}/boot/firmware" "${ROOTFS_DIR}/data"

mount "${BOOT_PART}" "${ROOTFS_DIR}/boot/firmware"
MOUNTED_DIRS+=("${ROOTFS_DIR}/boot/firmware")

mount "${DATA_PART}" "${ROOTFS_DIR}/data"
MOUNTED_DIRS+=("${ROOTFS_DIR}/data")

_pass "Rootfs mount stack active"

_step "Provision rootfs in chroot"
bash "${CHROOT_SETUP_SCRIPT}" \
    --rootfs-dir "${ROOTFS_DIR}" \
    --repo-root "${REPO_ROOT}" \
    --boot-uuid "${BOOT_UUID}" \
    --root-uuid "${ROOT_UUID}" \
    --data-uuid "${DATA_UUID}" \
    --debian-release "${DEBIAN_RELEASE}" \
    --debian-mirror "${DEBIAN_MIRROR}" \
    --rpi-mirror "${RPI_MIRROR}" \
    --paper-mc-version "${PAPER_MC_VERSION}" \
    --paper-build "${PAPER_BUILD}" \
    --log-file "${LOG_FILE}"

_pass "Chroot provisioning complete"

_step "Flush and detach image"
sync
cleanup
_pass "Loop device and mounts released"

_step "Compress image with zstd -19"
zstd -19 --force --threads=0 -o "${IMAGE_ZST_FILE}" "${IMAGE_FILE}"

if [[ "${KEEP_RAW_IMAGE}" == false ]]; then
    rm -f "${IMAGE_FILE}"
fi

(
    cd "${OUTPUT_DIR}"
    sha256sum "$(basename "${IMAGE_ZST_FILE}")" > "$(basename "${IMAGE_ZST_SHA256_FILE}")"
)

_pass "Artifact ready: ${IMAGE_ZST_FILE}"
_pass "Checksum: ${IMAGE_ZST_SHA256_FILE}"

if [[ "${KEEP_WORK_DIR}" == false ]]; then
    rm -rf "${WORK_DIR}"
    _pass "Cleaned work directory"
else
    _warn "Work directory retained: ${WORK_DIR}"
fi

_log "Build completed successfully"
