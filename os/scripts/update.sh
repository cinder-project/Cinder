#!/usr/bin/env bash
# =============================================================================
# Cinder OS — update.sh
# Safe Cinder Core JAR update with pre-update backup, rollback slot, and
# optional service restart
#
# Responsibilities:
#   - Validate the incoming JAR (non-empty, readable, version string present)
#   - Back up the current JAR into a rollback slot before replacing
#   - Atomically replace the active JAR via staging + rename
#   - Optionally restart cinder.service after update
#   - Provide a rollback command on failure
#
# Usage:
#   ./update.sh --jar <path-to-new-jar> [--restart] [--dry-run]
#
#   --jar <path>    Path to the new cinder-*.jar to deploy (required)
#   --restart       Restart cinder.service after successful update
#   --dry-run       Validate and print actions without writing anything
#
# Exit codes:
#   0   Update completed (or dry-run completed)
#   1   Fatal error (missing jar, validation failed, deploy failed)
#   3   Rollback required — old JAR restored automatically
#
# Rollback:
#   The previous JAR is preserved at:
#     ${CINDER_JAR_DIR}/cinder-core.jar.rollback
#   To restore manually:
#     cp /opt/cinder/cinder-core/cinder-core.jar.rollback \
#        /opt/cinder/cinder-core/cinder-core.jar
#     systemctl restart cinder
# =============================================================================

set -uo pipefail
IFS=$'\n\t'

# ── Configuration ─────────────────────────────────────────────────────────────

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_JAR_DIR:=${CINDER_BASE_DIR}/cinder-core}"
: "${CINDER_JAR:=${CINDER_JAR_DIR}/cinder-core.jar}"
: "${CINDER_STAGING_DIR:=${CINDER_BASE_DIR}/staging}"
: "${CINDER_LOG_DIR:=${CINDER_BASE_DIR}/logs}"
: "${JAVA_HOME:=/usr/lib/jvm/java-21-openjdk-arm64}"

readonly ROLLBACK_JAR="${CINDER_JAR}.rollback"
readonly TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
readonly LOG_FILE="${CINDER_LOG_DIR}/update-${TIMESTAMP}.log"
readonly JAVA_BIN="${JAVA_HOME}/bin/java"

NEW_JAR=""
RESTART=false
DRY_RUN=false

# ── Argument parsing ──────────────────────────────────────────────────────────

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar)      NEW_JAR="${2:?--jar requires a path}"; shift 2 ;;
        --restart)  RESTART=true; shift ;;
        --dry-run)  DRY_RUN=true; shift ;;
        *)          echo "[update] Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "${NEW_JAR}" ]]; then
    echo "[update] FAIL  --jar <path> is required" >&2
    exit 1
fi

# ── Output helpers ────────────────────────────────────────────────────────────

mkdir -p "${CINDER_LOG_DIR}"

_log()  { echo "[update] $(date -u +%H:%M:%SZ)  $*" | tee -a "${LOG_FILE}"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() {
    _log "FAIL    $*"
    _log "Update aborted. Active JAR unchanged."
    exit 1
}

_log "Cinder update starting — source=${NEW_JAR}"
[[ "${DRY_RUN}" == true ]] && _log "Dry-run mode — no files will be written"

# ── Validate incoming JAR ─────────────────────────────────────────────────────

if [[ ! -f "${NEW_JAR}" ]]; then
    _fail "JAR not found: ${NEW_JAR}"
fi

if [[ ! -r "${NEW_JAR}" ]]; then
    _fail "JAR not readable: ${NEW_JAR}"
fi

JAR_SIZE_BYTES="$(stat -c%s "${NEW_JAR}")"
if (( JAR_SIZE_BYTES < 65536 )); then
    _fail "JAR is suspiciously small (${JAR_SIZE_BYTES} bytes) — refusing to deploy"
fi
_pass "JAR size: ${JAR_SIZE_BYTES} bytes"

# Verify the JAR is a valid zip/JAR (magic bytes PK\x03\x04).
JAR_MAGIC="$(xxd -l 4 -p "${NEW_JAR}" 2>/dev/null || od -An -N4 -tx1 "${NEW_JAR}" | tr -d ' ')"
if [[ "${JAR_MAGIC}" != "504b0304" ]]; then
    _fail "JAR does not have a valid ZIP magic number — not a real JAR"
fi
_pass "JAR magic bytes valid"

# Extract version string from the JAR manifest.
NEW_VERSION="$("${JAVA_BIN}" -cp "${NEW_JAR}" -XshowSettings:none \
    2>/dev/null || true)"
NEW_VERSION="$(unzip -p "${NEW_JAR}" META-INF/MANIFEST.MF 2>/dev/null \
    | grep -i "Implementation-Version" | head -1 | cut -d: -f2 | tr -d ' \r')"

if [[ -z "${NEW_VERSION}" ]]; then
    _warn "Could not read Implementation-Version from manifest — proceeding anyway"
    NEW_VERSION="unknown"
fi
_pass "New version: ${NEW_VERSION}"

# ── Check current JAR ─────────────────────────────────────────────────────────

CURRENT_VERSION="unknown"
if [[ -f "${CINDER_JAR}" ]]; then
    CURRENT_VERSION="$(unzip -p "${CINDER_JAR}" META-INF/MANIFEST.MF 2>/dev/null \
        | grep -i "Implementation-Version" | head -1 | cut -d: -f2 | tr -d ' \r' || echo "unknown")"
    _log "Current version: ${CURRENT_VERSION}"
else
    _warn "No existing JAR at ${CINDER_JAR} — fresh install"
fi

# ── Dry-run exit ──────────────────────────────────────────────────────────────

if [[ "${DRY_RUN}" == true ]]; then
    _log "Would deploy: ${NEW_JAR} → ${CINDER_JAR}"
    _log "Would save rollback: ${ROLLBACK_JAR}"
    [[ "${RESTART}" == true ]] && _log "Would restart: cinder.service"
    _log "Dry-run complete."
    exit 0
fi

# ── Stop service if restart requested (stop before, restart after) ────────────

if [[ "${RESTART}" == true ]]; then
    _log "Stopping cinder.service before update..."
    if ! systemctl stop cinder 2>>"${LOG_FILE}"; then
        _warn "cinder.service stop failed or service was not running — continuing"
    else
        _pass "cinder.service stopped"
    fi
fi

# ── Save rollback slot ────────────────────────────────────────────────────────

if [[ -f "${CINDER_JAR}" ]]; then
    _log "Saving rollback slot: ${ROLLBACK_JAR}"
    if ! cp --preserve=timestamps "${CINDER_JAR}" "${ROLLBACK_JAR}"; then
        _fail "Failed to save rollback slot — aborting update"
    fi
    _pass "Rollback slot saved (version=${CURRENT_VERSION})"
fi

# ── Atomic deploy ─────────────────────────────────────────────────────────────

mkdir -p "${CINDER_STAGING_DIR}" "${CINDER_JAR_DIR}"

STAGING_JAR="${CINDER_STAGING_DIR}/cinder-core-${TIMESTAMP}.jar"

_log "Copying to staging: ${STAGING_JAR}"
if ! cp "${NEW_JAR}" "${STAGING_JAR}"; then
    _fail "Failed to copy JAR to staging"
fi

_log "Deploying: ${STAGING_JAR} → ${CINDER_JAR}"
if ! mv "${STAGING_JAR}" "${CINDER_JAR}"; then
    rm -f "${STAGING_JAR}"
    # Attempt rollback.
    if [[ -f "${ROLLBACK_JAR}" ]]; then
        _log "Deploy failed — restoring rollback..."
        cp "${ROLLBACK_JAR}" "${CINDER_JAR}" && _log "Rollback restored."
    fi
    _fail "Atomic rename failed — see log"
fi

chmod 644 "${CINDER_JAR}"
_pass "JAR deployed: ${CINDER_JAR} (version=${NEW_VERSION})"

# ── Restart service ───────────────────────────────────────────────────────────

if [[ "${RESTART}" == true ]]; then
    _log "Starting cinder.service..."
    if ! systemctl start cinder 2>>"${LOG_FILE}"; then
        _warn "cinder.service failed to start after update"
        _log "Rollback with: cp ${ROLLBACK_JAR} ${CINDER_JAR} && systemctl start cinder"
        exit 3
    fi
    _pass "cinder.service started"
fi

# ── Summary ───────────────────────────────────────────────────────────────────

_log "Update complete: ${CURRENT_VERSION} → ${NEW_VERSION}"
_log "Rollback slot:   ${ROLLBACK_JAR}"
_log "Log:             ${LOG_FILE}"
