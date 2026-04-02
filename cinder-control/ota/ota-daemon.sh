#!/usr/bin/env bash
# =============================================================================
# Cinder Control - ota-daemon.sh
# One-shot OTA check/apply run. Intended to be launched by systemd timer.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_JAR:=/opt/cinder/cinder-core/cinder-core.jar}"
: "${CINDER_UPDATE_SCRIPT:=/opt/cinder/scripts/update.sh}"
: "${CINDER_VERIFY_SCRIPT:=/opt/cinder/cinder-control/ota/ota-verify.sh}"
: "${OTA_RELEASE_ENDPOINT:=https://api.github.com/repos/cinder-project/Cinder/releases/latest}"
: "${OTA_STAGING_DIR:=/data/staging/ota}"
: "${OTA_TIMEOUT_START_SECS:=60}"

SYSTEMD_NOTIFY_BIN="$(command -v systemd-notify || true)"

_log()  { echo "[ota-daemon] $(date -u +%H:%M:%SZ)  $*"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() { _log "FAIL    $*" >&2; exit 1; }

_notify_status() {
    if [[ -n "${SYSTEMD_NOTIFY_BIN}" ]]; then
        "${SYSTEMD_NOTIFY_BIN}" "STATUS=$1" >/dev/null 2>&1 || true
    fi
}

_read_installed_version() {
    if [[ ! -f "${CINDER_JAR}" ]]; then
        echo ""
        return
    fi

    unzip -p "${CINDER_JAR}" META-INF/MANIFEST.MF 2>/dev/null \
        | awk -F': ' 'tolower($1)=="implementation-version"{print $2; exit}' \
        | tr -d '\r'
}

_wait_for_service_healthy() {
    local service_name="$1"
    local timeout_secs="$2"
    local elapsed=0

    while (( elapsed < timeout_secs )); do
        if systemctl is-active --quiet "${service_name}"; then
            return 0
        fi
        sleep 2
        elapsed=$(( elapsed + 2 ))
    done

    return 1
}

_rollback_and_restart() {
    local rollback_jar="${CINDER_JAR}.rollback"

    if [[ ! -f "${rollback_jar}" ]]; then
        _fail "Rollback jar missing: ${rollback_jar}"
    fi

    cp -f "${rollback_jar}" "${CINDER_JAR}"
    chmod 0644 "${CINDER_JAR}"

    systemctl restart cinder.service
    if _wait_for_service_healthy cinder.service "${OTA_TIMEOUT_START_SECS}"; then
        _pass "Rollback succeeded; cinder.service is healthy"
    else
        _fail "Rollback attempted but cinder.service did not become healthy"
    fi
}

command -v curl >/dev/null 2>&1 || _fail "curl is required"
command -v jq >/dev/null 2>&1 || _fail "jq is required"
command -v unzip >/dev/null 2>&1 || _fail "unzip is required"
command -v systemctl >/dev/null 2>&1 || _fail "systemctl is required"

[[ -x "${CINDER_UPDATE_SCRIPT}" ]] || _fail "update.sh missing or not executable: ${CINDER_UPDATE_SCRIPT}"
[[ -x "${CINDER_VERIFY_SCRIPT}" ]] || _fail "ota-verify.sh missing or not executable: ${CINDER_VERIFY_SCRIPT}"

mkdir -p "${OTA_STAGING_DIR}"

_notify_status "Polling release endpoint"
_log "Polling latest release metadata"

RELEASE_JSON="$(curl -fsSL "${OTA_RELEASE_ENDPOINT}")"
LATEST_TAG="$(jq -r '.tag_name // empty' <<<"${RELEASE_JSON}")"

[[ -n "${LATEST_TAG}" ]] || _fail "Release endpoint did not return tag_name"

JAR_URL="$(jq -r '.assets[] | select(.name | test("cinder-.*-all\\.jar$")) | .browser_download_url' <<<"${RELEASE_JSON}" | head -1)"
SHA_URL="$(jq -r '.assets[] | select(.name | test("cinder-.*-all\\.jar\\.sha256$")) | .browser_download_url' <<<"${RELEASE_JSON}" | head -1)"

[[ -n "${JAR_URL}" ]] || _fail "No cinder-*-all.jar asset found in latest release"
[[ -n "${SHA_URL}" ]] || _fail "No cinder-*-all.jar.sha256 asset found in latest release"

INSTALLED_VERSION="$(_read_installed_version)"
if [[ -n "${INSTALLED_VERSION}" && "${INSTALLED_VERSION}" == "${LATEST_TAG}" ]]; then
    _pass "Already on latest version (${INSTALLED_VERSION})"
    _notify_status "No update required"
    exit 0
fi

_notify_status "Downloading release assets"
JAR_FILE="${OTA_STAGING_DIR}/$(basename "${JAR_URL}")"
SHA_FILE="${OTA_STAGING_DIR}/$(basename "${SHA_URL}")"

curl -fsSL "${JAR_URL}" -o "${JAR_FILE}"
curl -fsSL "${SHA_URL}" -o "${SHA_FILE}"

_notify_status "Verifying update payload"
"${CINDER_VERIFY_SCRIPT}" --jar "${JAR_FILE}" --sha256-file "${SHA_FILE}"

_notify_status "Deploying update"
"${CINDER_UPDATE_SCRIPT}" --jar "${JAR_FILE}" --restart

_notify_status "Validating runtime health"
if _wait_for_service_healthy cinder.service "${OTA_TIMEOUT_START_SECS}"; then
    _pass "OTA update applied successfully: ${LATEST_TAG}"
    _notify_status "OTA update successful"
    exit 0
fi

_warn "cinder.service did not become healthy within ${OTA_TIMEOUT_START_SECS}s; rolling back"
_notify_status "OTA rollback in progress"
_rollback_and_restart
