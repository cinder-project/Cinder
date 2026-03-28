#!/usr/bin/env bash
# =============================================================================
# Cinder OS — firstboot.sh
# One-shot first-boot provisioning script for Raspberry Pi 4 / Pi 400
#
# Managed by: cinder-firstboot.service (Type=oneshot, RemainAfterExit=yes)
# Install path: /opt/cinder/scripts/firstboot.sh
#
# This script runs exactly once on the first power-on after flashing a
# Cinder OS image. It performs all provisioning that cannot be baked into
# the image (hostname, SSH keys, user passwords, partition expansion) and
# leaves the system ready for steady-state operation.
#
# On completion it writes /opt/cinder/.firstboot-complete and disables
# its own systemd unit so it never runs again.
#
# Provisioning steps:
#   1.  Expand root filesystem to fill the SD card / USB drive
#   2.  Set hostname (from /boot/firmware/cinder-hostname.txt if present)
#   3.  Regenerate SSH host keys (image ships without host keys)
#   4.  Create 'cinder' user with locked password (SSH key auth only)
#   5.  Create 'cinder-admin' user for operator access (sudo, SSH only)
#   6.  Create /opt/cinder directory structure with correct ownership
#   7.  Deploy systemd units (enable cinder.service)
#   8.  Configure nftables firewall (ports 22, 25565)
#   9.  Configure fail2ban for SSH
#  10.  Set Pi 4 CPU governor to ondemand (default for non-benchmark presets)
#  11.  Apply /boot/firmware/config.txt Pi tuning overrides
#  12.  Write /opt/cinder/logs/firstboot.log with full provisioning record
#  13.  Mark complete and disable cinder-firstboot.service
#
# Operator customisation:
#   Place these files on the boot FAT32 partition before first boot to
#   override defaults without modifying the image:
#
#     /boot/firmware/cinder-hostname.txt  — desired hostname (one line)
#     /boot/firmware/cinder-pubkey.txt    — SSH public key for cinder-admin
#     /boot/firmware/cinder-preset.txt    — default preset name
#     /boot/firmware/cinder-password.txt  — initial cinder-admin password
#                                           (plaintext; file is shredded after use)
#
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

# ── Identity ──────────────────────────────────────────────────────────────────

readonly SCRIPT_VERSION="0.1.0"
readonly START_TIME="$(date -u +%Y%m%dT%H%M%SZ)"
readonly BOOT_PART="/boot/firmware"
readonly CINDER_BASE="/opt/cinder"
readonly CINDER_USER="cinder"
readonly CINDER_ADMIN_USER="cinder-admin"
readonly CINDER_UID=1001
readonly CINDER_ADMIN_UID=1002
readonly COMPLETE_MARKER="${CINDER_BASE}/.firstboot-complete"
readonly LOG_FILE="${CINDER_BASE}/logs/firstboot-${START_TIME}.log"

# ── Bail immediately if already provisioned ───────────────────────────────────

if [[ -f "${COMPLETE_MARKER}" ]]; then
    echo "[firstboot] Already provisioned (${COMPLETE_MARKER} exists). Exiting."
    exit 0
fi

# ── Logging ───────────────────────────────────────────────────────────────────

mkdir -p "${CINDER_BASE}/logs"
exec > >(tee -a "${LOG_FILE}") 2>&1

log()  { echo "[firstboot] $(date -u +%H:%M:%S)  $*"; }
step() { echo ""; echo "[firstboot] ══════════════════════════════════════"; echo "[firstboot]  $*"; echo "[firstboot] ══════════════════════════════════════"; }
warn() { echo "[firstboot] WARN  $*"; }
die()  { echo "[firstboot] FATAL $*" >&2; exit 1; }

log "Cinder OS first-boot provisioning — v${SCRIPT_VERSION}"
log "Start time: ${START_TIME}"

[[ "${EUID}" -eq 0 ]] || die "Must run as root"

# ── Read operator customisation files ─────────────────────────────────────────

_read_boot_file() {
    local file="${BOOT_PART}/$1"
    if [[ -f "${file}" ]]; then
        tr -d '[:space:]' < "${file}"
    else
        echo ""
    fi
}

CUSTOM_HOSTNAME="$(_read_boot_file cinder-hostname.txt)"
CUSTOM_PUBKEY="$(_read_boot_file cinder-pubkey.txt)"
CUSTOM_PRESET="$(_read_boot_file cinder-preset.txt)"
CUSTOM_PASSWORD="$(_read_boot_file cinder-password.txt)"

HOSTNAME="${CUSTOM_HOSTNAME:-cinderpi}"
PRESET="${CUSTOM_PRESET:-survival}"

# ── Step 1: Expand root filesystem ───────────────────────────────────────────

step "1/13 Expanding root filesystem"

ROOT_DEVICE=$(findmnt -n -o SOURCE / | sed 's/p[0-9]*$//')
ROOT_PART=$(findmnt -n -o SOURCE /)
PART_NUM=$(echo "${ROOT_PART}" | grep -oP '\d+$')

log "Root device: ${ROOT_DEVICE}, partition: ${ROOT_PART} (#${PART_NUM})"

if command -v raspi-config &>/dev/null; then
    raspi-config --expand-rootfs || warn "raspi-config expand failed — partition may already be expanded"
else
    # Manual expansion via parted + resize2fs
    parted -s "${ROOT_DEVICE}" resizepart "${PART_NUM}" 100% || warn "parted resizepart failed"
    partprobe "${ROOT_DEVICE}" 2>/dev/null || true
    sleep 1
    resize2fs "${ROOT_PART}" 2>/dev/null || warn "resize2fs failed — filesystem may already be at max size"
fi

log "Root filesystem expansion complete"

# ── Step 2: Set hostname ──────────────────────────────────────────────────────

step "2/13 Setting hostname: ${HOSTNAME}"

echo "${HOSTNAME}" > /etc/hostname
hostnamectl set-hostname "${HOSTNAME}" 2>/dev/null || hostname "${HOSTNAME}"

if ! grep -q "${HOSTNAME}" /etc/hosts; then
    echo "127.0.1.1    ${HOSTNAME}" >> /etc/hosts
fi

log "Hostname set to: ${HOSTNAME}"

# ── Step 3: Regenerate SSH host keys ─────────────────────────────────────────

step "3/13 Regenerating SSH host keys"

rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub
ssh-keygen -A
log "SSH host keys regenerated"

# Harden SSH config
cat > /etc/ssh/sshd_config.d/cinder-hardening.conf << 'EOF'
PermitRootLogin no
PasswordAuthentication yes
PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys
X11Forwarding no
AllowTcpForwarding no
MaxAuthTries 3
LoginGraceTime 20
ClientAliveInterval 60
ClientAliveCountMax 3
EOF

log "SSH hardening config applied"

# ── Step 4: Create 'cinder' service user ─────────────────────────────────────

step "4/13 Creating cinder service user (uid=${CINDER_UID})"

if ! id "${CINDER_USER}" &>/dev/null; then
    useradd \
        --uid "${CINDER_UID}" \
        --system \
        --no-create-home \
        --home-dir "${CINDER_BASE}" \
        --shell /usr/sbin/nologin \
        --comment "Cinder Core service account" \
        "${CINDER_USER}"
    log "Created user: ${CINDER_USER} (uid=${CINDER_UID})"
else
    log "User ${CINDER_USER} already exists — skipping creation"
fi

# Lock the service account password — SSH key auth only via cinder-admin
passwd -l "${CINDER_USER}" 2>/dev/null || true

# ── Step 5: Create 'cinder-admin' operator user ───────────────────────────────

step "5/13 Creating cinder-admin operator user (uid=${CINDER_ADMIN_UID})"

if ! id "${CINDER_ADMIN_USER}" &>/dev/null; then
    useradd \
        --uid "${CINDER_ADMIN_UID}" \
        --create-home \
        --shell /bin/bash \
        --comment "Cinder operator account" \
        "${CINDER_ADMIN_USER}"
    log "Created user: ${CINDER_ADMIN_USER} (uid=${CINDER_ADMIN_UID})"
fi

# Add to sudo group
usermod -aG sudo "${CINDER_ADMIN_USER}"

# Set password if provided, otherwise force password change on first login
if [[ -n "${CUSTOM_PASSWORD}" ]]; then
    echo "${CINDER_ADMIN_USER}:${CUSTOM_PASSWORD}" | chpasswd
    log "Password set for ${CINDER_ADMIN_USER} from boot customisation file"
    shred -u "${BOOT_PART}/cinder-password.txt" 2>/dev/null || \
        rm -f "${BOOT_PART}/cinder-password.txt"
else
    # Set a default password and force change
    echo "${CINDER_ADMIN_USER}:cinderpi" | chpasswd
    chage -d 0 "${CINDER_ADMIN_USER}"
    log "Default password set for ${CINDER_ADMIN_USER} — change required on first login"
fi

# Install SSH public key if provided
if [[ -n "${CUSTOM_PUBKEY}" ]]; then
    local admin_home
    admin_home=$(getent passwd "${CINDER_ADMIN_USER}" | cut -d: -f6)
    mkdir -p "${admin_home}/.ssh"
    echo "${CUSTOM_PUBKEY}" >> "${admin_home}/.ssh/authorized_keys"
    chmod 700 "${admin_home}/.ssh"
    chmod 600 "${admin_home}/.ssh/authorized_keys"
    chown -R "${CINDER_ADMIN_USER}:${CINDER_ADMIN_USER}" "${admin_home}/.ssh"
    log "SSH public key installed for ${CINDER_ADMIN_USER}"
fi

# ── Step 6: Create /opt/cinder directory structure ────────────────────────────

step "6/13 Creating /opt/cinder directory structure"

declare -a CINDER_DIRS=(
    "${CINDER_BASE}"
    "${CINDER_BASE}/world"
    "${CINDER_BASE}/logs"
    "${CINDER_BASE}/backups"
    "${CINDER_BASE}/staging"
    "${CINDER_BASE}/config"
    "${CINDER_BASE}/plugins"
    "${CINDER_BASE}/mods"
    "${CINDER_BASE}/cinder-core"
    "${CINDER_BASE}/cinder-runtime/presets"
    "${CINDER_BASE}/cinder-runtime/launch"
    "${CINDER_BASE}/scripts"
)

for dir in "${CINDER_DIRS[@]}"; do
    mkdir -p "${dir}"
    log "  ${dir}"
done

chown -R "${CINDER_USER}:${CINDER_USER}" "${CINDER_BASE}"
chmod 750 "${CINDER_BASE}"
chmod 770 "${CINDER_BASE}/world"
chmod 770 "${CINDER_BASE}/logs"
chmod 770 "${CINDER_BASE}/backups"
chmod 770 "${CINDER_BASE}/staging"

log "Directory structure created and ownership set"

# Write import allowlist skeleton if not present
if [[ ! -f "${CINDER_BASE}/config/import-allowlist.sha256" ]]; then
    cat > "${CINDER_BASE}/config/import-allowlist.sha256" << 'EOF'
# Cinder USB Import Allowlist
# Add SHA-256 hashes of approved files, one per line.
# Format: <sha256hash>  <filename>
# Generate with: sha256sum <file>
#
# When this file is present, usb-import.sh will reject any file
# whose hash is not listed here. Leave empty to allow all validated files.
EOF
    chown "${CINDER_USER}:${CINDER_USER}" "${CINDER_BASE}/config/import-allowlist.sha256"
    log "Created import allowlist skeleton"
fi

# Write default server.properties if not present
if [[ ! -f "${CINDER_BASE}/config/server.properties" ]]; then
    cat > "${CINDER_BASE}/config/server.properties" << EOF
# Cinder server.properties
# Generated by firstboot.sh on ${START_TIME}
# Edit and restart cinder.service to apply changes.

server-port=25565
max-players=20
online-mode=true
difficulty=normal
gamemode=survival
level-name=world
motd=Cinder \u2014 Raspberry Pi 4 Minecraft Server
view-distance=8
simulation-distance=6
spawn-protection=16
EOF
    chown "${CINDER_USER}:${CINDER_USER}" "${CINDER_BASE}/config/server.properties"
    log "Created default server.properties"
fi

# Apply custom preset if specified
if [[ -n "${CUSTOM_PRESET}" ]]; then
    mkdir -p /etc/systemd/system/cinder.service.d
    cat > /etc/systemd/system/cinder.service.d/preset.conf << EOF
[Service]
Environment=CINDER_PRESET=${CUSTOM_PRESET}
EOF
    log "Preset override written: ${CUSTOM_PRESET}"
fi

# ── Step 7: Enable systemd services ──────────────────────────────────────────

step "7/13 Enabling systemd services"

systemctl daemon-reload

systemctl enable cinder.service
log "Enabled: cinder.service"

systemctl enable fail2ban.service 2>/dev/null && log "Enabled: fail2ban.service" || warn "fail2ban not installed"
systemctl enable nftables.service 2>/dev/null && log "Enabled: nftables.service" || warn "nftables not installed"

# ── Step 8: Configure nftables firewall ───────────────────────────────────────

step "8/13 Configuring nftables firewall"

cat > /etc/nftables.conf << 'EOF'
#!/usr/sbin/nft -f
# Cinder OS — nftables firewall
# Managed by firstboot.sh; edit and run 'nft -f /etc/nftables.conf' to apply.

flush ruleset

table inet filter {
    chain input {
        type filter hook input priority 0; policy drop;

        # Accept established and related connections
        ct state established,related accept

        # Accept loopback
        iif lo accept

        # Accept ICMP (ping)
        ip  protocol icmp  accept
        ip6 nexthdr  icmpv6 accept

        # SSH (rate limited to 4 connections/minute to complement fail2ban)
        tcp dport 22 ct state new limit rate 4/minute accept

        # Minecraft player connections
        tcp dport 25565 accept

        # Drop everything else
        log prefix "[nft-drop] " drop
    }

    chain forward {
        type filter hook forward priority 0; policy drop;
    }

    chain output {
        type filter hook output priority 0; policy accept;
    }
}
EOF

nft -f /etc/nftables.conf 2>/dev/null && log "nftables rules applied" || warn "nftables apply failed — check /etc/nftables.conf"

# ── Step 9: Configure fail2ban ────────────────────────────────────────────────

step "9/13 Configuring fail2ban"

if [[ -d /etc/fail2ban ]]; then
    cat > /etc/fail2ban/jail.d/cinder-ssh.conf << 'EOF'
[sshd]
enabled  = true
port     = ssh
filter   = sshd
backend  = systemd
maxretry = 5
bantime  = 3600
findtime = 600
EOF
    log "fail2ban SSH jail configured"
else
    warn "fail2ban not installed — skipping jail configuration"
fi

# ── Step 10: CPU governor ─────────────────────────────────────────────────────

step "10/13 Setting CPU governor"

GOVERNOR="ondemand"
if [[ "${PRESET}" == "benchmark" || "${PRESET}" == "extreme" ]]; then
    GOVERNOR="performance"
fi

if [[ -f /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]]; then
    for gov_path in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
        echo "${GOVERNOR}" > "${gov_path}" 2>/dev/null || true
    done
    log "CPU governor set to: ${GOVERNOR}"

    # Persist via cpufrequtils if available
    if command -v cpufreq-set &>/dev/null; then
        for cpu in /sys/devices/system/cpu/cpu[0-9]*; do
            cpufreq-set -c "$(basename "${cpu}" | tr -d 'cpu')" -g "${GOVERNOR}" 2>/dev/null || true
        done
    fi
else
    warn "CPU frequency scaling not available — governor not set"
fi

# ── Step 11: Pi 4 tuning ──────────────────────────────────────────────────────

step "11/13 Applying Pi 4 tuning"

CONFIG_TXT="${BOOT_PART}/config.txt"
if [[ -f "${CONFIG_TXT}" ]]; then
    # Ensure Cinder's required config entries are present
    declare -A REQUIRED_SETTINGS=(
        ["gpu_mem"]="16"
        ["arm_64bit"]="1"
        ["disable_overscan"]="1"
    )

    for key in "${!REQUIRED_SETTINGS[@]}"; do
        if ! grep -q "^${key}=" "${CONFIG_TXT}"; then
            echo "${key}=${REQUIRED_SETTINGS[${key}]}" >> "${CONFIG_TXT}"
            log "  Added to config.txt: ${key}=${REQUIRED_SETTINGS[${key}]}"
        fi
    done

    log "Pi 4 boot config verified"
else
    warn "config.txt not found at ${CONFIG_TXT}"
fi

# Set swappiness low — swap on SD kills tick consistency
sysctl -w vm.swappiness=10 2>/dev/null || true
echo "vm.swappiness=10" >> /etc/sysctl.d/99-cinder.conf
log "vm.swappiness=10 applied"

# Disable transparent huge pages — reduces GC pause unpredictability
if [[ -f /sys/kernel/mm/transparent_hugepage/enabled ]]; then
    echo "madvise" > /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null || true
    echo "never"   > /sys/kernel/mm/transparent_hugepage/defrag  2>/dev/null || true
    log "Transparent huge pages set to madvise/never"
fi

# ── Step 12: Write firstboot log summary ──────────────────────────────────────

step "12/13 Writing provisioning summary"

cat >> "${LOG_FILE}" << EOF

══════════════════════════════════════════════════════════
Cinder OS First-Boot Provisioning Summary
══════════════════════════════════════════════════════════
Completed : $(date -u +%Y-%m-%dT%H:%M:%SZ)
Hostname  : ${HOSTNAME}
Preset    : ${PRESET}
CPU Gov   : ${GOVERNOR}
Users     : ${CINDER_USER} (uid=${CINDER_UID}) ${CINDER_ADMIN_USER} (uid=${CINDER_ADMIN_UID})
Base dir  : ${CINDER_BASE}
SSH keys  : regenerated
Firewall  : nftables (ports 22, 25565)
fail2ban  : SSH jail (5 retries / 1h ban)
══════════════════════════════════════════════════════════

Next steps:
  1. SSH in:  ssh cinder-admin@${HOSTNAME}.local
  2. Deploy Cinder Core JAR to: ${CINDER_BASE}/cinder-core/build/libs/
  3. Start server: sudo systemctl start cinder
  4. Check status: sudo systemctl status cinder
  5. View logs:    journalctl -u cinder -f

EOF

log "Provisioning log written to: ${LOG_FILE}"

# ── Step 13: Mark complete and disable service ────────────────────────────────

step "13/13 Marking first-boot complete"

date -u +%Y-%m-%dT%H:%M:%SZ > "${COMPLETE_MARKER}"
chown root:root "${COMPLETE_MARKER}"
chmod 444 "${COMPLETE_MARKER}"

systemctl disable cinder-firstboot.service 2>/dev/null || true

log "First-boot provisioning complete."
log "The cinder-firstboot.service will not run again."
log "Cinder Core will start automatically on next boot."
log ""
log "Reboot recommended to apply filesystem expansion and kernel parameters."
