#!/usr/bin/env bash
# =============================================================================
# Cinder OS - chroot-setup.sh
# Build and configure a Debian 12 (bookworm) ARM64 rootfs for Cinder OS.
#
# Responsibilities:
#   - Run debootstrap (foreign + second stage) for arm64
#   - Enter chroot via QEMU on x86_64 hosts
#   - Install Cinder runtime dependencies and Raspberry Pi kernel/firmware
#   - Configure systemd base services, SSH hardening, and fail2ban jail
#   - Create cinder/cinder-admin users
#   - Create /data mount topology and /opt/cinder symlink model
#   - Install Cinder scripts/runtime assets into /opt/cinder
#
# Usage:
#   sudo ./chroot-setup.sh \
#     --rootfs-dir ./os/build/work/rootfs \
#     --repo-root . \
#     --boot-uuid <uuid> \
#     --root-uuid <uuid> \
#     --data-uuid <uuid>
#
# Notes:
#   - Non-interactive by design (CI-safe).
#   - Requires Linux host with mount/chroot privileges.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${ROOTFS_DIR:=}"
: "${REPO_ROOT:=$(cd "${SCRIPT_DIR}/../.." && pwd)}"
: "${PACKAGES_MANIFEST:=${SCRIPT_DIR}/packages.list}"
: "${DEBIAN_RELEASE:=bookworm}"
: "${DEBIAN_MIRROR:=https://deb.debian.org/debian}"
: "${RPI_MIRROR:=http://archive.raspberrypi.com/debian}"
: "${TARGET_ARCH:=arm64}"
: "${HOSTNAME_VALUE:=cinder}"
: "${BOOT_UUID:=}"
: "${ROOT_UUID:=}"
: "${DATA_UUID:=}"
: "${KEEP_QEMU:=false}"
: "${SKIP_DEBOOTSTRAP:=false}"
: "${CINDER_UID:=1001}"
: "${CINDER_ADMIN_UID:=1002}"
: "${CINDER_USER:=cinder}"
: "${CINDER_ADMIN_USER:=cinder-admin}"
: "${LOG_FILE:=}"

MOUNTED_DIRS=()

_log()  { echo "[chroot-setup] $(date -u +%H:%M:%SZ)  $*"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() { _log "FAIL    $*" >&2; cleanup; exit 1; }
_step() {
    echo
    _log "============================================"
    _log "$*"
    _log "============================================"
}

_usage() {
    cat <<'EOF'
Usage:
  chroot-setup.sh --rootfs-dir <path> --boot-uuid <uuid> --root-uuid <uuid> --data-uuid <uuid> [options]

Required:
  --rootfs-dir <path>      Root filesystem path to build/configure
  --boot-uuid <uuid>       UUID for /boot/firmware partition
  --root-uuid <uuid>       UUID for / partition
  --data-uuid <uuid>       UUID for /data partition

Optional:
  --repo-root <path>       Cinder repository root (default: auto-detect)
  --packages <path>        Package manifest (default: os/build/packages.list)
  --debian-release <name>  Debian release (default: bookworm)
  --debian-mirror <url>    Debian mirror (default: deb.debian.org)
  --rpi-mirror <url>       Raspberry Pi apt mirror
  --hostname <name>        Base hostname in image (default: cinder)
  --skip-debootstrap       Configure existing rootfs only
  --keep-qemu              Keep qemu-aarch64-static in rootfs after setup
  --log-file <path>        Append logs to file
  -h, --help               Show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rootfs-dir)
            ROOTFS_DIR="${2:?--rootfs-dir requires a value}"
            shift 2
            ;;
        --repo-root)
            REPO_ROOT="${2:?--repo-root requires a value}"
            shift 2
            ;;
        --packages)
            PACKAGES_MANIFEST="${2:?--packages requires a value}"
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
        --hostname)
            HOSTNAME_VALUE="${2:?--hostname requires a value}"
            shift 2
            ;;
        --boot-uuid)
            BOOT_UUID="${2:?--boot-uuid requires a value}"
            shift 2
            ;;
        --root-uuid)
            ROOT_UUID="${2:?--root-uuid requires a value}"
            shift 2
            ;;
        --data-uuid)
            DATA_UUID="${2:?--data-uuid requires a value}"
            shift 2
            ;;
        --skip-debootstrap)
            SKIP_DEBOOTSTRAP=true
            shift
            ;;
        --keep-qemu)
            KEEP_QEMU=true
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

if [[ -z "${ROOTFS_DIR}" ]]; then
    _fail "--rootfs-dir is required"
fi

if [[ -z "${BOOT_UUID}" || -z "${ROOT_UUID}" || -z "${DATA_UUID}" ]]; then
    _fail "--boot-uuid, --root-uuid, and --data-uuid are required"
fi

ROOTFS_DIR="$(cd "$(dirname "${ROOTFS_DIR}")" && pwd)/$(basename "${ROOTFS_DIR}")"
REPO_ROOT="$(cd "${REPO_ROOT}" && pwd)"

if [[ -n "${LOG_FILE}" ]]; then
    mkdir -p "$(dirname "${LOG_FILE}")"
    exec > >(tee -a "${LOG_FILE}") 2>&1
fi

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
}

trap cleanup EXIT

_step "Preflight checks"

[[ "${EUID}" -eq 0 ]] || _fail "Must run as root"

_require_cmd debootstrap
_require_cmd chroot
_require_cmd mount
_require_cmd umount
_require_cmd rsync
_require_cmd apt-get
_require_cmd sed
_require_cmd awk
_require_cmd cut
_require_cmd curl
_require_cmd gpg
_require_cmd tar
_require_cmd findmnt
_require_cmd install
_require_cmd xargs
_require_cmd sort
_require_cmd uniq

if [[ ! -f "${PACKAGES_MANIFEST}" ]]; then
    _fail "Package manifest not found: ${PACKAGES_MANIFEST}"
fi

if [[ ! -d "${REPO_ROOT}" ]]; then
    _fail "Repository root not found: ${REPO_ROOT}"
fi

if [[ ! -d "${REPO_ROOT}/os/scripts" ]]; then
    _fail "Repository root does not look like Cinder tree: ${REPO_ROOT}"
fi

HOST_ARCH="$(uname -m)"
if [[ "${HOST_ARCH}" == "x86_64" ]]; then
    _require_cmd qemu-aarch64-static
    if [[ ! -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]]; then
        _fail "binfmt entry for qemu-aarch64 is not enabled"
    fi
    _pass "Cross-build mode active (x86_64 -> arm64 via QEMU)"
else
    _pass "Native host architecture detected: ${HOST_ARCH}"
fi

_step "Debootstrap base rootfs"

mkdir -p "${ROOTFS_DIR}"

if [[ "${SKIP_DEBOOTSTRAP}" == false ]]; then
    debootstrap \
        --arch="${TARGET_ARCH}" \
        --foreign \
        --include=ca-certificates \
        "${DEBIAN_RELEASE}" \
        "${ROOTFS_DIR}" \
        "${DEBIAN_MIRROR}"

    if [[ "${HOST_ARCH}" == "x86_64" ]]; then
        install -m 0755 "$(command -v qemu-aarch64-static)" "${ROOTFS_DIR}/usr/bin/qemu-aarch64-static"
    fi

    chroot "${ROOTFS_DIR}" /debootstrap/debootstrap --second-stage
    _pass "Debootstrap completed"
else
    _warn "Skipping debootstrap stage (--skip-debootstrap)"
fi

_step "Mount chroot pseudo-filesystems"

mkdir -p "${ROOTFS_DIR}/dev" "${ROOTFS_DIR}/proc" "${ROOTFS_DIR}/sys" "${ROOTFS_DIR}/run"

mount --bind /dev "${ROOTFS_DIR}/dev"
MOUNTED_DIRS+=("${ROOTFS_DIR}/dev")

mount --bind /proc "${ROOTFS_DIR}/proc"
MOUNTED_DIRS+=("${ROOTFS_DIR}/proc")

mount --bind /sys "${ROOTFS_DIR}/sys"
MOUNTED_DIRS+=("${ROOTFS_DIR}/sys")

mount --bind /run "${ROOTFS_DIR}/run"
MOUNTED_DIRS+=("${ROOTFS_DIR}/run")

_pass "Chroot mount stack ready"

_step "Configure apt repositories"

cat > "${ROOTFS_DIR}/etc/apt/sources.list" <<EOF
# Cinder OS Debian package sources
deb ${DEBIAN_MIRROR} ${DEBIAN_RELEASE} main contrib non-free non-free-firmware
deb ${DEBIAN_MIRROR} ${DEBIAN_RELEASE}-updates main contrib non-free non-free-firmware
deb ${DEBIAN_MIRROR} ${DEBIAN_RELEASE}-backports main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security ${DEBIAN_RELEASE}-security main contrib non-free non-free-firmware
EOF

mkdir -p "${ROOTFS_DIR}/usr/share/keyrings" "${ROOTFS_DIR}/etc/apt/sources.list.d"

RPI_KEY_TMP="$(mktemp)"
curl -fsSL "${RPI_MIRROR}/raspberrypi.gpg.key" -o "${RPI_KEY_TMP}"
gpg --dearmor --yes --output "${ROOTFS_DIR}/usr/share/keyrings/raspberrypi-archive-keyring.gpg" "${RPI_KEY_TMP}"
rm -f "${RPI_KEY_TMP}"

cat > "${ROOTFS_DIR}/etc/apt/sources.list.d/raspberrypi.list" <<EOF
# Raspberry Pi kernel and firmware source
deb [signed-by=/usr/share/keyrings/raspberrypi-archive-keyring.gpg] ${RPI_MIRROR} ${DEBIAN_RELEASE} main
EOF

_pass "Apt sources configured (Debian + Raspberry Pi archive)"

_step "Install packages"

parse_package_manifest() {
    local manifest_path="$1"
    local out_file="$2"

    awk '
        function normalize_package_name(pkg) {
            # Keep compatibility with legacy manifests that used non-Debian names.
            if (pkg == "shadow") return "passwd"
            if (pkg == "schedutils") return "util-linux"
            return pkg
        }

        {
            line=$0
            sub(/#.*/, "", line)
            gsub(/^[ \t]+|[ \t]+$/, "", line)
            if (line == "") next
            if (line ~ /^(SECTION:|EXCLUDE:|BUILD_ONLY:)/) next
            print normalize_package_name(line)
        }
    ' "${manifest_path}" | sort -u > "${out_file}"
}

PKG_FILE_HOST="$(mktemp)"
parse_package_manifest "${PACKAGES_MANIFEST}" "${PKG_FILE_HOST}"

cat >> "${PKG_FILE_HOST}" <<'EOF'
raspberrypi-kernel
raspberrypi-bootloader
watchdog
zstd
EOF

sort -u -o "${PKG_FILE_HOST}" "${PKG_FILE_HOST}"

install -m 0644 "${PKG_FILE_HOST}" "${ROOTFS_DIR}/tmp/cinder-packages.txt"
rm -f "${PKG_FILE_HOST}"

chroot "${ROOTFS_DIR}" bash -euo pipefail -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update

    JAVA21_CANDIDATE="$(apt-cache policy openjdk-21-jre-headless | awk "/Candidate:/ {print \\$2; exit}")"
    if [[ -z "${JAVA21_CANDIDATE}" || "${JAVA21_CANDIDATE}" == "(none)" ]]; then
        echo "[chroot-setup] WARN    openjdk-21-jre-headless has no install candidate; using fallback runtime"
        sed -i "/^openjdk-21-jre-headless[[:space:]]*$/d" /tmp/cinder-packages.txt
    fi

    xargs -a /tmp/cinder-packages.txt apt-get install -y --no-install-recommends
    apt-get clean
    rm -rf /var/lib/apt/lists/*
    rm -f /tmp/cinder-packages.txt
'

if [[ ! -x "${ROOTFS_DIR}/usr/lib/jvm/java-21-openjdk-arm64/bin/java" ]]; then
    _warn "Installing Java 21 runtime fallback (Temurin)"

    JAVA21_ARCHIVE="$(mktemp)"
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jre/hotspot/normal/eclipse?project=jdk" -o "${JAVA21_ARCHIVE}"

    mkdir -p "${ROOTFS_DIR}/usr/lib/jvm"
    rm -rf "${ROOTFS_DIR}/usr/lib/jvm/java-21-openjdk-arm64"
    mkdir -p "${ROOTFS_DIR}/usr/lib/jvm/java-21-openjdk-arm64"
    tar -xzf "${JAVA21_ARCHIVE}" -C "${ROOTFS_DIR}/usr/lib/jvm/java-21-openjdk-arm64" --strip-components=1
    rm -f "${JAVA21_ARCHIVE}"

    chroot "${ROOTFS_DIR}" bash -euo pipefail -c '
        update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-21-openjdk-arm64/bin/java 2121
        update-alternatives --set java /usr/lib/jvm/java-21-openjdk-arm64/bin/java
    '

    _pass "Java 21 runtime fallback installed"
else
    _pass "Java 21 runtime installed from apt"
fi

_pass "Base package set installed"

_step "Configure system identity and fstab"

echo "${HOSTNAME_VALUE}" > "${ROOTFS_DIR}/etc/hostname"

cat > "${ROOTFS_DIR}/etc/hosts" <<EOF
127.0.0.1   localhost
127.0.1.1   ${HOSTNAME_VALUE}
::1         localhost ip6-localhost ip6-loopback
EOF

mkdir -p "${ROOTFS_DIR}/boot/firmware" "${ROOTFS_DIR}/data"

cat > "${ROOTFS_DIR}/etc/fstab" <<EOF
# Cinder OS filesystem table
# <file system>          <mount point>   <type>  <options>                          <dump> <pass>
UUID=${ROOT_UUID}        /               ext4    defaults,noatime,errors=remount-ro 0      1
UUID=${BOOT_UUID}        /boot/firmware  vfat    defaults,noatime,umask=0077        0      2
UUID=${DATA_UUID}        /data           ext4    defaults,noatime,nodiratime         0      2
tmpfs                    /tmp            tmpfs   defaults,size=256m,noexec           0      0
EOF

chroot "${ROOTFS_DIR}" bash -euo pipefail -c '
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    echo "en_US.UTF-8 UTF-8" > /etc/locale.gen
    locale-gen
    update-locale LANG=en_US.UTF-8
    ln -sf /usr/share/zoneinfo/UTC /etc/localtime
    echo "UTC" > /etc/timezone
'

_pass "Hostname, locale/timezone, and fstab configured"

_step "Install boot payload"

if [[ -f "${REPO_ROOT}/os/boot/config.txt" ]]; then
    install -m 0644 "${REPO_ROOT}/os/boot/config.txt" "${ROOTFS_DIR}/boot/firmware/config.txt"
else
    _fail "Missing required boot file: ${REPO_ROOT}/os/boot/config.txt"
fi

if [[ -f "${REPO_ROOT}/os/boot/cmdline.txt" ]]; then
    install -m 0644 "${REPO_ROOT}/os/boot/cmdline.txt" "${ROOTFS_DIR}/boot/firmware/cmdline.txt"
else
    _fail "Missing required boot file: ${REPO_ROOT}/os/boot/cmdline.txt"
fi

cat > "${ROOTFS_DIR}/boot/firmware/cinder-firstboot.txt" <<'EOF'
Cinder OS first-boot customisation files:
  cinder-hostname.txt   -> hostname (single line)
  cinder-pubkey.txt     -> SSH public key for cinder-admin
  cinder-preset.txt     -> startup preset (survival/event/low-power/benchmark/extreme)
  cinder-password.txt   -> initial cinder-admin password (deleted on first boot)
EOF

_pass "Boot partition files installed"

_step "Install Cinder runtime content"

mkdir -p \
    "${ROOTFS_DIR}/opt/cinder" \
    "${ROOTFS_DIR}/opt/cinder/cinder-runtime" \
    "${ROOTFS_DIR}/opt/cinder/cinder-control" \
    "${ROOTFS_DIR}/opt/cinder/scripts" \
    "${ROOTFS_DIR}/opt/cinder/cinder-core" \
    "${ROOTFS_DIR}/opt/cinder/dashboard"

rsync -a --delete "${REPO_ROOT}/cinder-runtime/" "${ROOTFS_DIR}/opt/cinder/cinder-runtime/"
rsync -a --delete "${REPO_ROOT}/cinder-control/" "${ROOTFS_DIR}/opt/cinder/cinder-control/"
rsync -a "${REPO_ROOT}/os/scripts/" "${ROOTFS_DIR}/opt/cinder/scripts/"
rsync -a "${REPO_ROOT}/os/usb-import/" "${ROOTFS_DIR}/opt/cinder/scripts/"

if [[ -d "${REPO_ROOT}/dashboard" ]]; then
    rsync -a --delete "${REPO_ROOT}/dashboard/" "${ROOTFS_DIR}/opt/cinder/dashboard/"
fi

if [[ -f "${REPO_ROOT}/os/services/cinder.service" ]]; then
    install -m 0644 "${REPO_ROOT}/os/services/cinder.service" "${ROOTFS_DIR}/etc/systemd/system/cinder.service"
else
    _fail "Missing required systemd unit: ${REPO_ROOT}/os/services/cinder.service"
fi

if [[ -f "${REPO_ROOT}/os/rootfs/etc/nftables.conf" ]]; then
    install -m 0644 "${REPO_ROOT}/os/rootfs/etc/nftables.conf" "${ROOTFS_DIR}/etc/nftables.conf"
fi

if [[ -f "${REPO_ROOT}/os/rootfs/etc/sysctl.d/cinder.conf" ]]; then
    mkdir -p "${ROOTFS_DIR}/etc/sysctl.d"
    install -m 0644 "${REPO_ROOT}/os/rootfs/etc/sysctl.d/cinder.conf" "${ROOTFS_DIR}/etc/sysctl.d/cinder.conf"
fi

if [[ -f "${REPO_ROOT}/os/rootfs/etc/systemd/system/cinder-watchdog.service" ]]; then
    install -m 0644 "${REPO_ROOT}/os/rootfs/etc/systemd/system/cinder-watchdog.service" "${ROOTFS_DIR}/etc/systemd/system/cinder-watchdog.service"
fi

if [[ -f "${REPO_ROOT}/os/rootfs/etc/systemd/system/cinder-ota.service" ]]; then
    install -m 0644 "${REPO_ROOT}/os/rootfs/etc/systemd/system/cinder-ota.service" "${ROOTFS_DIR}/etc/systemd/system/cinder-ota.service"
fi

if [[ -f "${REPO_ROOT}/os/rootfs/etc/systemd/system/cinder-ota.timer" ]]; then
    install -m 0644 "${REPO_ROOT}/os/rootfs/etc/systemd/system/cinder-ota.timer" "${ROOTFS_DIR}/etc/systemd/system/cinder-ota.timer"
fi

JAR_CANDIDATE=""
if compgen -G "${REPO_ROOT}/build/libs/cinder-*-all.jar" >/dev/null; then
    JAR_CANDIDATE="$(ls -1 "${REPO_ROOT}"/build/libs/cinder-*-all.jar | head -1)"
    install -m 0644 "${JAR_CANDIDATE}" "${ROOTFS_DIR}/opt/cinder/cinder-core/cinder-core.jar"
    _pass "Installed runtime jar: $(basename "${JAR_CANDIDATE}")"
else
    _warn "No built fat jar found in build/libs; image will require jar injection/update before first launch"
fi

find "${ROOTFS_DIR}/opt/cinder/scripts" -type f -name '*.sh' -exec chmod 0750 {} \;

if [[ -d "${ROOTFS_DIR}/opt/cinder/cinder-control/ota" ]]; then
    find "${ROOTFS_DIR}/opt/cinder/cinder-control/ota" -type f -name '*.sh' -exec chmod 0750 {} \;
fi

_pass "Cinder runtime files installed"

_step "Create users and data topology"

chroot "${ROOTFS_DIR}" bash -euo pipefail -c "
    getent group ${CINDER_USER} >/dev/null 2>&1 || groupadd --gid ${CINDER_UID} ${CINDER_USER}

    if ! id -u ${CINDER_USER} >/dev/null 2>&1; then
        useradd --uid ${CINDER_UID} --gid ${CINDER_UID} --home-dir /opt/cinder --shell /usr/sbin/nologin --system ${CINDER_USER}
    fi

    if ! id -u ${CINDER_ADMIN_USER} >/dev/null 2>&1; then
        useradd --uid ${CINDER_ADMIN_UID} --create-home --shell /bin/bash ${CINDER_ADMIN_USER}
    fi

    usermod -aG sudo ${CINDER_ADMIN_USER}

    mkdir -p /data/world/datapacks /data/logs /data/backups /data/staging /data/plugins /data/mods /data/resourcepacks /data/downloads /data/metrics
    mkdir -p /opt/cinder/config /opt/cinder/cinder-runtime/presets /opt/cinder/cinder-runtime/launch

    rm -rf /opt/cinder/world /opt/cinder/logs /opt/cinder/backups /opt/cinder/staging /opt/cinder/plugins /opt/cinder/mods /opt/cinder/resourcepacks /opt/cinder/downloads /opt/cinder/metrics

    ln -s /data/world /opt/cinder/world
    ln -s /data/logs /opt/cinder/logs
    ln -s /data/backups /opt/cinder/backups
    ln -s /data/staging /opt/cinder/staging
    ln -s /data/plugins /opt/cinder/plugins
    ln -s /data/mods /opt/cinder/mods
    ln -s /data/resourcepacks /opt/cinder/resourcepacks
    ln -s /data/downloads /opt/cinder/downloads
    ln -s /data/metrics /opt/cinder/metrics

    chown -R ${CINDER_USER}:${CINDER_USER} /opt/cinder /data
    chmod 0750 /opt/cinder
"

_pass "Users and /data symlink topology configured"

_step "Security and service configuration"

mkdir -p "${ROOTFS_DIR}/etc/ssh/sshd_config.d"
cat > "${ROOTFS_DIR}/etc/ssh/sshd_config.d/cinder-hardening.conf" <<'EOF'
# Cinder OS SSH hardening baseline
PermitRootLogin no
PasswordAuthentication no
PermitEmptyPasswords no
PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys
MaxAuthTries 3
LoginGraceTime 30
EOF

mkdir -p "${ROOTFS_DIR}/etc/fail2ban/jail.d"
cat > "${ROOTFS_DIR}/etc/fail2ban/jail.d/cinder-ssh.conf" <<'EOF'
[sshd]
enabled = true
port = ssh
backend = systemd
maxretry = 5
findtime = 10m
bantime = 10m
EOF

cat > "${ROOTFS_DIR}/etc/systemd/system/cinder-firstboot.service" <<'EOF'
[Unit]
Description=Cinder OS first-boot provisioning
ConditionPathExists=/opt/cinder/.firstboot-pending
After=local-fs.target
Before=cinder.service

[Service]
Type=oneshot
ExecStart=/opt/cinder/scripts/firstboot.sh
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF

touch "${ROOTFS_DIR}/opt/cinder/.firstboot-pending"

chroot "${ROOTFS_DIR}" bash -euo pipefail -c '
    systemctl enable cinder-firstboot.service
    systemctl enable cinder.service
    systemctl enable cinder-watchdog.service 2>/dev/null || true
    systemctl enable cinder-ota.timer 2>/dev/null || true
    systemctl enable nftables.service 2>/dev/null || true
    systemctl enable fail2ban.service 2>/dev/null || true
    systemctl disable apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || true
'

_pass "Service units and security baseline configured"

_step "Finalize rootfs"

chroot "${ROOTFS_DIR}" bash -euo pipefail -c '
    apt-get clean
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
    truncate -s 0 /etc/machine-id
'

if [[ "${KEEP_QEMU}" == false ]]; then
    rm -f "${ROOTFS_DIR}/usr/bin/qemu-aarch64-static"
fi

_pass "Rootfs prepared successfully"
_log "Completed: ${ROOTFS_DIR}"
