#!/usr/bin/env bash
# =============================================================================
# Cinder OS - watchdog-setup.sh
# Arm the BCM2835 watchdog and ensure userspace watchdog service is active.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${WATCHDOG_TIMEOUT:=15}"
: "${WATCHDOG_INTERVAL:=5}"
: "${WATCHDOG_DEVICE:=/dev/watchdog}"
: "${WATCHDOG_CONF:=/etc/watchdog.conf}"

_log()  { echo "[watchdog-setup] $(date -u +%H:%M:%SZ)  $*"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() { _log "FAIL    $*" >&2; exit 1; }

[[ "${EUID}" -eq 0 ]] || _fail "Must run as root"

command -v modprobe >/dev/null 2>&1 || _fail "modprobe is required"
command -v systemctl >/dev/null 2>&1 || _fail "systemctl is required"

if ! modprobe bcm2835_wdt 2>/dev/null; then
    _warn "Kernel module bcm2835_wdt could not be loaded (may already be built-in)"
fi

if [[ ! -e "${WATCHDOG_DEVICE}" && -e /dev/watchdog0 ]]; then
    WATCHDOG_DEVICE="/dev/watchdog0"
fi

[[ -e "${WATCHDOG_DEVICE}" ]] || _fail "No watchdog device found (/dev/watchdog or /dev/watchdog0)"

cat > "${WATCHDOG_CONF}" <<EOF
# Cinder-managed watchdog configuration
watchdog-device = ${WATCHDOG_DEVICE}
watchdog-timeout = ${WATCHDOG_TIMEOUT}
interval = ${WATCHDOG_INTERVAL}
max-load-1 = 24
min-memory = 8192
EOF

chmod 0644 "${WATCHDOG_CONF}"
_pass "Watchdog config written: ${WATCHDOG_CONF}"

# Ensure watchdog userspace daemon is active for /dev/watchdog keepalive pings.
systemctl enable watchdog.service >/dev/null 2>&1 || _warn "Could not enable watchdog.service"
systemctl restart watchdog.service >/dev/null 2>&1 || _fail "Could not start watchdog.service"

if systemctl is-active --quiet watchdog.service; then
    _pass "watchdog.service is active"
else
    _fail "watchdog.service is not active"
fi
