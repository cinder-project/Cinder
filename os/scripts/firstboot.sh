#!/usr/bin/env bash
# =============================================================================
# Cinder OS - firstboot.sh
# One-shot first boot provisioning for flash-and-run Cinder OS images.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

readonly START_TS="$(date -u +%Y%m%dT%H%M%SZ)"
readonly BOOT_DIR="/boot/firmware"
readonly DATA_DIR="/data"
readonly CINDER_BASE="/opt/cinder"
readonly COMPLETE_MARKER="${CINDER_BASE}/.firstboot-complete"
readonly PENDING_MARKER="${CINDER_BASE}/.firstboot-pending"
readonly PRESTART_CHECK_SCRIPT="${CINDER_BASE}/scripts/prestart-check.sh"

: "${CINDER_USER:=cinder}"
: "${CINDER_ADMIN_USER:=cinder-admin}"
: "${CINDER_UID:=1001}"
: "${CINDER_ADMIN_UID:=1002}"

LOG_DIR="${DATA_DIR}/logs"
LOG_FILE=""

_log()  { echo "[firstboot] $(date -u +%H:%M:%SZ)  $*"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() { _log "FAIL    $*" >&2; exit 1; }

_step() {
    echo
    _log "============================================"
    _log "$*"
    _log "============================================"
}

_read_boot_value() {
    local file_path="${BOOT_DIR}/$1"
    if [[ -f "${file_path}" ]]; then
        tr -d '\r' < "${file_path}" | head -n1 | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
    else
        echo ""
    fi
}

_valid_preset() {
    case "$1" in
        survival|event|low-power|benchmark|extreme)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

_expand_data_partition() {
    local root_source disk data_part

    root_source="$(findmnt -n -o SOURCE / || true)"
    if [[ -z "${root_source}" || ! -b "${root_source}" ]]; then
        _warn "Could not determine root block device; skipping partition expansion"
        return 0
    fi

    if [[ "${root_source}" =~ p2$ ]]; then
        data_part="${root_source/%p2/p3}"
    elif [[ "${root_source}" =~ 2$ ]]; then
        data_part="${root_source/%2/3}"
    else
        _warn "Root device format not recognized (${root_source}); skipping partition expansion"
        return 0
    fi

    if [[ "${root_source}" =~ ^(/dev/mmcblk[0-9]+)p2$ ]]; then
        disk="${BASH_REMATCH[1]}"
    elif [[ "${root_source}" =~ ^(/dev/nvme[0-9]+n[0-9]+)p2$ ]]; then
        disk="${BASH_REMATCH[1]}"
    elif [[ "${root_source}" =~ ^(/dev/sd[a-z])2$ ]]; then
        disk="${BASH_REMATCH[1]}"
    else
        local pkname
        pkname="$(lsblk -no PKNAME "${root_source}" 2>/dev/null || true)"
        if [[ -n "${pkname}" ]]; then
            disk="/dev/${pkname}"
        else
            _warn "Could not derive disk backing ${root_source}; skipping partition expansion"
            return 0
        fi
    fi

    if [[ ! -b "${disk}" || ! -b "${data_part}" ]]; then
        _warn "Data partition not available (${disk}, ${data_part}); skipping partition expansion"
        return 0
    fi

    if ! parted -s "${disk}" resizepart 3 100%; then
        _warn "parted resizepart failed; continuing with existing partition size"
        return 0
    fi

    partprobe "${disk}" || true
    udevadm settle || true

    if mountpoint -q "${DATA_DIR}"; then
        resize2fs "${data_part}" || _warn "resize2fs failed on mounted ${data_part}"
    else
        e2fsck -pf "${data_part}" || true
        resize2fs "${data_part}" || _warn "resize2fs failed on ${data_part}"
    fi

    _pass "Expanded data partition: ${data_part}"
}

if [[ -f "${COMPLETE_MARKER}" ]]; then
    echo "[firstboot] Already complete (${COMPLETE_MARKER}); exiting"
    exit 0
fi

[[ "${EUID}" -eq 0 ]] || _fail "Must run as root"

if ! mountpoint -q "${DATA_DIR}"; then
    mount "${DATA_DIR}" >/dev/null 2>&1 || _fail "Unable to mount ${DATA_DIR}"
fi

mkdir -p "${LOG_DIR}" || _fail "Unable to create ${LOG_DIR}"

LOG_FILE="${LOG_DIR}/firstboot-${START_TS}.log"
exec > >(tee -a "${LOG_FILE}") 2>&1

_step "Starting firstboot provisioning"
_pass "Log file: ${LOG_FILE}"

CUSTOM_HOSTNAME="$(_read_boot_value cinder-hostname.txt)"
CUSTOM_PUBKEY="$(_read_boot_value cinder-pubkey.txt)"
CUSTOM_PRESET="$(_read_boot_value cinder-preset.txt)"
CUSTOM_EULA="$(_read_boot_value cinder-eula.txt)"
CUSTOM_PASSWORD="$(_read_boot_value cinder-password.txt)"

HOSTNAME_VALUE="${CUSTOM_HOSTNAME:-cinder}"
PRESET_VALUE="${CUSTOM_PRESET:-survival}"

_step "Expanding /data partition"
_expand_data_partition
mountpoint -q "${DATA_DIR}" || mount "${DATA_DIR}" || _warn "Failed to mount ${DATA_DIR}"

_step "Preparing /data directories and /opt/cinder links"
mkdir -p \
    "${DATA_DIR}/world/datapacks" \
    "${DATA_DIR}/logs" \
    "${DATA_DIR}/backups" \
    "${DATA_DIR}/staging" \
    "${DATA_DIR}/plugins" \
    "${DATA_DIR}/mods" \
    "${DATA_DIR}/resourcepacks" \
    "${DATA_DIR}/downloads" \
    "${DATA_DIR}/metrics"

mkdir -p "${CINDER_BASE}" "${CINDER_BASE}/server" "${CINDER_BASE}/config" "${CINDER_BASE}/scripts"

for name in world logs backups staging plugins mods resourcepacks downloads metrics; do
    if [[ ! -L "${CINDER_BASE}/${name}" ]]; then
        rm -rf "${CINDER_BASE}/${name}"
        ln -s "${DATA_DIR}/${name}" "${CINDER_BASE}/${name}"
    fi
done

_pass "Data topology ready"

_step "Setting hostname"
echo "${HOSTNAME_VALUE}" > /etc/hostname
hostnamectl set-hostname "${HOSTNAME_VALUE}" 2>/dev/null || hostname "${HOSTNAME_VALUE}"
if ! grep -q "^127.0.1.1[[:space:]]\+${HOSTNAME_VALUE}$" /etc/hosts; then
    echo "127.0.1.1 ${HOSTNAME_VALUE}" >> /etc/hosts
fi
_pass "Hostname set: ${HOSTNAME_VALUE}"

_step "Creating service and admin users"
if ! getent group "${CINDER_USER}" >/dev/null 2>&1; then
    groupadd --gid "${CINDER_UID}" "${CINDER_USER}"
fi

if ! id -u "${CINDER_USER}" >/dev/null 2>&1; then
    useradd --uid "${CINDER_UID}" --gid "${CINDER_UID}" --home-dir "${CINDER_BASE}" --system --shell /usr/sbin/nologin "${CINDER_USER}"
fi
passwd -l "${CINDER_USER}" >/dev/null 2>&1 || true

if ! id -u "${CINDER_ADMIN_USER}" >/dev/null 2>&1; then
    useradd --uid "${CINDER_ADMIN_UID}" --create-home --shell /bin/bash "${CINDER_ADMIN_USER}"
fi

usermod -aG sudo "${CINDER_ADMIN_USER}"

if [[ -n "${CUSTOM_PASSWORD}" ]]; then
    echo "${CINDER_ADMIN_USER}:${CUSTOM_PASSWORD}" | chpasswd
    rm -f "${BOOT_DIR}/cinder-password.txt"
    _pass "Applied admin password from ${BOOT_DIR}/cinder-password.txt"
else
    echo "${CINDER_ADMIN_USER}:cinder" | chpasswd
    chage -d 0 "${CINDER_ADMIN_USER}"
    _warn "No custom password file found; set default and force change for ${CINDER_ADMIN_USER}"
fi

chown -R "${CINDER_USER}:${CINDER_USER}" "${CINDER_BASE}" "${DATA_DIR}"

_step "Applying preset override"
if _valid_preset "${PRESET_VALUE}"; then
    mkdir -p /etc/systemd/system/cinder.service.d
    cat > /etc/systemd/system/cinder.service.d/preset.conf <<EOF
[Service]
Environment=CINDER_PRESET=${PRESET_VALUE}
EOF
    _pass "Preset applied: ${PRESET_VALUE}"
else
    _warn "Invalid preset '${PRESET_VALUE}' ignored; keeping service default"
fi

EULA_VALUE_RAW="${CUSTOM_EULA:-true}"
EULA_VALUE="$(echo "${EULA_VALUE_RAW}" | tr '[:upper:]' '[:lower:]')"
case "${EULA_VALUE}" in
    1|yes|true)
        EULA_AUTO_ACCEPT="true"
        ;;
    0|no|false)
        EULA_AUTO_ACCEPT="false"
        ;;
    *)
        EULA_AUTO_ACCEPT="true"
        _warn "Invalid cinder-eula.txt value '${EULA_VALUE_RAW}', defaulting to auto-accept"
        ;;
esac

cat > /etc/systemd/system/cinder.service.d/eula.conf <<EOF
[Service]
Environment=CINDER_AUTO_ACCEPT_EULA=${EULA_AUTO_ACCEPT}
EOF

if [[ "${EULA_AUTO_ACCEPT}" == "true" ]]; then
    echo "eula=true" > "${CINDER_BASE}/eula.txt"
    _pass "EULA auto-accept enabled"
else
    echo "eula=false" > "${CINDER_BASE}/eula.txt"
    _warn "EULA auto-accept disabled; set ${CINDER_BASE}/eula.txt to eula=true before start"
fi

rm -f "${BOOT_DIR}/cinder-eula.txt"

_step "Configuring SSH hardening and key provisioning"
mkdir -p /etc/ssh/sshd_config.d
cat > /etc/ssh/sshd_config.d/cinder-hardening.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
PermitEmptyPasswords no
PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys
MaxAuthTries 3
LoginGraceTime 30
X11Forwarding no
AllowTcpForwarding no
ClientAliveInterval 60
ClientAliveCountMax 3
EOF

if [[ -n "${CUSTOM_PUBKEY}" ]]; then
    ADMIN_HOME="$(getent passwd "${CINDER_ADMIN_USER}" | cut -d: -f6)"
    mkdir -p "${ADMIN_HOME}/.ssh"
    touch "${ADMIN_HOME}/.ssh/authorized_keys"
    if ! grep -qxF "${CUSTOM_PUBKEY}" "${ADMIN_HOME}/.ssh/authorized_keys"; then
        echo "${CUSTOM_PUBKEY}" >> "${ADMIN_HOME}/.ssh/authorized_keys"
    fi
    chmod 700 "${ADMIN_HOME}/.ssh"
    chmod 600 "${ADMIN_HOME}/.ssh/authorized_keys"
    chown -R "${CINDER_ADMIN_USER}:${CINDER_ADMIN_USER}" "${ADMIN_HOME}/.ssh"
    _pass "Installed SSH public key for ${CINDER_ADMIN_USER}"
else
    _warn "No ${BOOT_DIR}/cinder-pubkey.txt provided; SSH password auth is disabled"
fi

systemctl restart ssh >/dev/null 2>&1 || systemctl restart sshd >/dev/null 2>&1 || _warn "Could not restart SSH service"

_step "Applying firewall and fail2ban"
if [[ -f /etc/nftables.conf ]]; then
    nft -f /etc/nftables.conf >/dev/null 2>&1 || _warn "Failed to apply nftables rules"
    systemctl enable nftables.service >/dev/null 2>&1 || true
    systemctl restart nftables.service >/dev/null 2>&1 || true
fi

if [[ -d /etc/fail2ban ]]; then
    mkdir -p /etc/fail2ban/jail.d
    cat > /etc/fail2ban/jail.d/cinder-ssh.conf <<'EOF'
[sshd]
enabled = true
port = ssh
backend = systemd
maxretry = 5
findtime = 10m
bantime = 10m
EOF
    systemctl enable fail2ban.service >/dev/null 2>&1 || true
    systemctl restart fail2ban.service >/dev/null 2>&1 || true
fi

_step "Enabling watchdog and OTA units"
systemctl daemon-reload

if systemctl list-unit-files | grep -q '^cinder-watchdog.service'; then
    systemctl enable cinder-watchdog.service >/dev/null 2>&1 || _warn "Failed to enable cinder-watchdog.service"
    systemctl start cinder-watchdog.service >/dev/null 2>&1 || _warn "Failed to start cinder-watchdog.service"
fi

if systemctl list-unit-files | grep -q '^cinder-ota.timer'; then
    systemctl enable cinder-ota.timer >/dev/null 2>&1 || _warn "Failed to enable cinder-ota.timer"
    systemctl start cinder-ota.timer >/dev/null 2>&1 || _warn "Failed to start cinder-ota.timer"
elif systemctl list-unit-files | grep -q '^cinder-ota.service'; then
    systemctl enable cinder-ota.service >/dev/null 2>&1 || _warn "Failed to enable cinder-ota.service"
fi

_step "Running prestart checks"
if [[ -x "${PRESTART_CHECK_SCRIPT}" ]]; then
    if "${PRESTART_CHECK_SCRIPT}"; then
        _pass "prestart-check.sh completed"
    else
        _warn "prestart-check.sh reported issues"
    fi
else
    _warn "Prestart script missing: ${PRESTART_CHECK_SCRIPT}"
fi

_step "Starting cinder.service"
systemctl enable cinder.service >/dev/null 2>&1 || _warn "Could not enable cinder.service"
if systemctl restart cinder.service; then
    _pass "cinder.service restart requested"
else
    _warn "cinder.service restart failed"
fi

if systemctl is-active --quiet cinder.service; then
    _pass "cinder.service is active"
else
    _warn "cinder.service is not active; inspect with: systemctl status cinder.service"
fi

_step "Marking firstboot completion"
mkdir -p "${CINDER_BASE}"
date -u +%Y-%m-%dT%H:%M:%SZ > "${COMPLETE_MARKER}"
chmod 0644 "${COMPLETE_MARKER}"
rm -f "${PENDING_MARKER}" /etc/cinder-firstboot-pending

systemctl disable cinder-firstboot.service >/dev/null 2>&1 || true

_pass "Firstboot complete marker written: ${COMPLETE_MARKER}"
_pass "Provisioning completed"
_log "Summary: hostname=${HOSTNAME_VALUE} preset=${PRESET_VALUE} log=${LOG_FILE}"
