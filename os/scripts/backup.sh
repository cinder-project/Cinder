#!/usr/bin/env bash
# =============================================================================
# Cinder OS — backup.sh
# World backup with atomic writes, timestamped archives, and retention pruning
#
# Responsibilities:
#   - Create a timestamped compressed backup of the world directory
#   - Write atomically (tar to staging, then move to backups/)
#   - Prune old backups beyond the retention limit
#   - Log every run to the Cinder log directory
#   - Refuse to run if the server is actively writing (check PID file)
#
# Usage:
#   ./backup.sh [--retention <n>] [--dry-run] [--force]
#
#   Defaults:
#     --retention  10      Keep 10 most recent backups
#     --dry-run    false   Print what would happen, write nothing
#     --force      false   Skip server-running safety check
#
# Exit codes:
#   0   Backup completed successfully
#   1   Fatal error (world dir missing, disk full, tar failed)
#   2   Skipped — server is running and --force not set
#
# Cron example (daily at 04:00):
#   0 4 * * * /opt/cinder/scripts/backup.sh --retention 14 >> /opt/cinder/logs/backup.log 2>&1
# =============================================================================

set -uo pipefail
IFS=$'\n\t'

# ── Configuration ─────────────────────────────────────────────────────────────

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_WORLD_DIR:=${CINDER_BASE_DIR}/world}"
: "${CINDER_BACKUP_DIR:=${CINDER_BASE_DIR}/backups}"
: "${CINDER_STAGING_DIR:=${CINDER_BASE_DIR}/staging}"
: "${CINDER_LOG_DIR:=${CINDER_BASE_DIR}/logs}"
: "${CINDER_PID_FILE:=${CINDER_BASE_DIR}/cinder.pid}"

readonly TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
readonly ARCHIVE_NAME="world-${TIMESTAMP}.tar.zst"
readonly ARCHIVE_STAGING="${CINDER_STAGING_DIR}/${ARCHIVE_NAME}"
readonly ARCHIVE_FINAL="${CINDER_BACKUP_DIR}/${ARCHIVE_NAME}"
readonly LOG_FILE="${CINDER_LOG_DIR}/backup-${TIMESTAMP}.log"

DEFAULT_RETENTION=10
DRY_RUN=false
FORCE=false

# ── Argument parsing ──────────────────────────────────────────────────────────

while [[ $# -gt 0 ]]; do
    case "$1" in
        --retention)  DEFAULT_RETENTION="${2:?--retention requires a value}"; shift 2 ;;
        --dry-run)    DRY_RUN=true; shift ;;
        --force)      FORCE=true; shift ;;
        *)            echo "[backup] Unknown argument: $1" >&2; exit 1 ;;
    esac
done

readonly RETENTION="${DEFAULT_RETENTION}"

# ── Output helpers ────────────────────────────────────────────────────────────

_log()  { echo "[backup] $(date -u +%H:%M:%SZ)  $*" | tee -a "${LOG_FILE}"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() { _log "FAIL    $*" >&2; exit 1; }
_dry()  { _log "DRY-RUN $*"; }

# ── Setup ─────────────────────────────────────────────────────────────────────

mkdir -p "${CINDER_LOG_DIR}"
_log "Cinder backup starting — archive=${ARCHIVE_NAME} retention=${RETENTION}"

if [[ "${DRY_RUN}" == true ]]; then
    _log "Dry-run mode — no files will be written"
fi

# ── Safety: refuse if server is running ──────────────────────────────────────

if [[ "${FORCE}" == false ]]; then
    if [[ -f "${CINDER_PID_FILE}" ]]; then
        PID="$(cat "${CINDER_PID_FILE}")"
        if kill -0 "${PID}" 2>/dev/null; then
            _log "Server is running (pid=${PID}). Use --force to back up anyway, or stop the server first."
            _log "Backup skipped."
            exit 2
        else
            _warn "Stale PID file found (pid=${PID} not running). Proceeding."
        fi
    fi
fi

# ── Validate world directory ──────────────────────────────────────────────────

if [[ ! -d "${CINDER_WORLD_DIR}" ]]; then
    _fail "World directory does not exist: ${CINDER_WORLD_DIR}"
fi

WORLD_SIZE_MB="$(du -sm "${CINDER_WORLD_DIR}" 2>/dev/null | cut -f1)"
_log "World directory: ${CINDER_WORLD_DIR} (${WORLD_SIZE_MB} MB)"

# ── Validate disk space ───────────────────────────────────────────────────────

AVAIL_BACKUP_MB="$(df -m "${CINDER_BACKUP_DIR}" 2>/dev/null | awk 'NR==2{print $4}')"
# Require 2× world size free to be safe (compression ratio varies)
REQUIRED_MB=$(( WORLD_SIZE_MB * 2 ))

if (( AVAIL_BACKUP_MB < REQUIRED_MB )); then
    _fail "Insufficient disk space in ${CINDER_BACKUP_DIR}: ${AVAIL_BACKUP_MB} MB available, ${REQUIRED_MB} MB required"
fi
_pass "Disk space: ${AVAIL_BACKUP_MB} MB available (need ${REQUIRED_MB} MB)"

# ── Create backup ─────────────────────────────────────────────────────────────

mkdir -p "${CINDER_STAGING_DIR}" "${CINDER_BACKUP_DIR}"

if [[ "${DRY_RUN}" == true ]]; then
    _dry "Would create: ${ARCHIVE_FINAL}"
    _dry "Would prune backups older than ${RETENTION} most recent"
    _log "Dry-run complete."
    exit 0
fi

_log "Creating archive at staging path: ${ARCHIVE_STAGING}"

START_NS="$(date +%s%N)"

# zstd level 3: good compression, fast enough on Pi 4 for world directories.
# --exclude=*.lock: skip any JVM lock files that may exist in world dir.
if ! tar \
    --use-compress-program "zstd -3 -T2" \
    --exclude="*.lock" \
    --exclude="session.lock" \
    -cf "${ARCHIVE_STAGING}" \
    -C "$(dirname "${CINDER_WORLD_DIR}")" \
    "$(basename "${CINDER_WORLD_DIR}")" \
    2>>"${LOG_FILE}"; then
    rm -f "${ARCHIVE_STAGING}"
    _fail "tar failed — archive removed from staging"
fi

END_NS="$(date +%s%N)"
ELAPSED_S=$(( (END_NS - START_NS) / 1000000000 ))

ARCHIVE_SIZE_MB="$(du -sm "${ARCHIVE_STAGING}" | cut -f1)"
_log "Archive created: ${ARCHIVE_SIZE_MB} MB in ${ELAPSED_S}s"

# Atomic rename from staging to backups — avoids partial archives in backups/.
if ! mv "${ARCHIVE_STAGING}" "${ARCHIVE_FINAL}"; then
    rm -f "${ARCHIVE_STAGING}"
    _fail "Failed to move archive from staging to ${CINDER_BACKUP_DIR}"
fi

_pass "Backup written: ${ARCHIVE_FINAL}"

# ── Retention pruning ─────────────────────────────────────────────────────────

_log "Pruning backups — keeping ${RETENTION} most recent"

# List archives sorted by modification time (newest first), skip the first N.
mapfile -t OLD_ARCHIVES < <(
    ls -t "${CINDER_BACKUP_DIR}"/world-*.tar.zst 2>/dev/null | tail -n +"$(( RETENTION + 1 ))"
)

if [[ ${#OLD_ARCHIVES[@]} -eq 0 ]]; then
    _pass "No old backups to prune"
else
    for archive in "${OLD_ARCHIVES[@]}"; do
        _log "Pruning: $(basename "${archive}")"
        rm -f "${archive}"
    done
    _pass "Pruned ${#OLD_ARCHIVES[@]} old backup(s)"
fi

# ── Summary ───────────────────────────────────────────────────────────────────

TOTAL_BACKUPS="$(ls "${CINDER_BACKUP_DIR}"/world-*.tar.zst 2>/dev/null | wc -l)"
_log "Backup complete. Total archives: ${TOTAL_BACKUPS}. Log: ${LOG_FILE}"
_log "Restore: tar --use-compress-program zstd -xf ${ARCHIVE_FINAL} -C ${CINDER_BASE_DIR}"
