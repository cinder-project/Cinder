#!/usr/bin/env bash
# =============================================================================
# Cinder OS — usb-import.sh
# USB mod / plugin / datapack / asset import pipeline
#
# Overview:
#   A user inserts a USB drive containing server files (mods, plugins,
#   datapacks, config assets). This script:
#
#     1. DETECT    — finds the USB block device and its filesystem
#     2. MOUNT     — mounts the device read-only under /mnt/cinder-usb
#     3. SCAN      — discovers importable files in the USB staging layout
#     4. VALIDATE  — checks file types, sizes, and SHA-256 checksums
#                    against an optional allowlist
#     5. BACKUP    — creates a versioned snapshot of the current server
#                    files that will be overwritten
#     6. STAGE     — copies validated files into a staging area
#     7. DEPLOY    — moves staged files to their final server destination
#     8. CLEANUP   — unmounts USB, records import manifest
#
#   Every stage is logged. On validation failure, the script exits before
#   any server files are touched. On deploy failure, the pre-import backup
#   is restored automatically.
#
# USB drive layout expected:
#   /cinder-import/
#     plugins/        → deployed to $SERVER_PLUGINS_DIR
#     mods/           → deployed to $SERVER_MODS_DIR
#     datapacks/      → deployed to $SERVER_DATAPACKS_DIR
#     config/         → deployed to $SERVER_CONFIG_DIR
#     checksums.sha256  (optional but strongly recommended)
#
#   The checksums.sha256 file should be generated on the source machine:
#     cd /path/to/cinder-import && find . -type f ! -name checksums.sha256 \
#       | sort | xargs sha256sum > checksums.sha256
#
# Allowlist:
#   If $ALLOWLIST_FILE exists, only files whose SHA-256 hashes appear in
#   the allowlist are permitted. Files not in the allowlist are rejected
#   and the import aborts. Set ALLOWLIST_FILE="" to disable allowlist
#   checking (not recommended for shared/public deployments).
#
# Rollback:
#   If deployment fails partway through, the script restores from the
#   versioned backup created in step 5. Rollback is also available
#   manually via: usb-import.sh --rollback <backup-id>
#
# Usage:
#   usb-import.sh [--device <dev>] [--dry-run] [--rollback <id>] [--list-backups]
#
#   --device <dev>     Specify USB device explicitly (e.g. /dev/sda1).
#                      If omitted, auto-detects the most recently connected
#                      USB mass storage device.
#   --dry-run          Scan and validate without deploying anything.
#   --rollback <id>    Restore a named backup. Use --list-backups to see IDs.
#   --list-backups     List available import backups and exit.
#   --force            Skip the server-running check (dangerous; use for
#                      offline maintenance only).
#
# Exit codes:
#   0   Success
#   1   Configuration / environment error
#   2   No USB device found
#   3   Mount failure
#   4   Validation failure (no files were touched)
#   5   Deploy failure (automatic rollback attempted)
#   6   Rollback failure (manual intervention required)
#
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

# ── Configuration ─────────────────────────────────────────────────────────────

readonly SCRIPT_VERSION="0.1.0"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Server directories — must match the paths used by Cinder Core and launch.sh
: "${CINDER_BASE_DIR:="/opt/cinder"}"
: "${SERVER_PLUGINS_DIR:="${CINDER_BASE_DIR}/plugins"}"
: "${SERVER_MODS_DIR:="${CINDER_BASE_DIR}/mods"}"
: "${SERVER_DATAPACKS_DIR:="${CINDER_BASE_DIR}/world/datapacks"}"
: "${SERVER_CONFIG_DIR:="${CINDER_BASE_DIR}/config"}"

# Staging and backup directories
: "${STAGING_DIR:="${CINDER_BASE_DIR}/staging/usb-import"}"
: "${BACKUP_BASE_DIR:="${CINDER_BASE_DIR}/backups/usb-import"}"

# USB mount point (created and removed by this script)
readonly USB_MOUNT="/mnt/cinder-usb"

# Expected import directory on the USB drive
readonly USB_IMPORT_SUBDIR="cinder-import"

# Allowlist file: SHA-256 hashes of approved files.
# Set to "" to disable allowlist enforcement.
: "${ALLOWLIST_FILE:="${CINDER_BASE_DIR}/config/import-allowlist.sha256"}"

# Maximum allowed size per import file (bytes). Default: 100 MB.
readonly MAX_FILE_SIZE_BYTES=$(( 100 * 1024 * 1024 ))

# File extension whitelist: only these extensions are considered importable.
readonly -a ALLOWED_EXTENSIONS=( "jar" "zip" "json" "toml" "yaml" "yml" "txt" "png" )

# Import manifest log
: "${MANIFEST_DIR:="${CINDER_BASE_DIR}/logs/import-manifests"}"

# ── Argument parsing ──────────────────────────────────────────────────────────

OPT_DEVICE=""
OPT_DRY_RUN=false
OPT_ROLLBACK=""
OPT_LIST_BACKUPS=false
OPT_FORCE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)    OPT_DEVICE="$2";   shift 2 ;;
        --dry-run)   OPT_DRY_RUN=true;  shift   ;;
        --rollback)  OPT_ROLLBACK="$2"; shift 2 ;;
        --list-backups) OPT_LIST_BACKUPS=true; shift ;;
        --force)     OPT_FORCE=true;    shift   ;;
        -h|--help)
            grep '^#' "$0" | head -60 | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "[usb-import] Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# ── Logging ───────────────────────────────────────────────────────────────────

mkdir -p "${MANIFEST_DIR}"
IMPORT_ID="$(date -u +%Y%m%dT%H%M%SZ)"
IMPORT_LOG="${MANIFEST_DIR}/import-${IMPORT_ID}.log"

log()  { local m="[$(date -u +%H:%M:%S)] [usb-import] $*";        echo "${m}"; echo "${m}" >> "${IMPORT_LOG}"; }
warn() { local m="[$(date -u +%H:%M:%S)] [usb-import:WARN]  $*";  echo "${m}" >&2; echo "${m}" >> "${IMPORT_LOG}"; }
die()  { local m="[$(date -u +%H:%M:%S)] [usb-import:FATAL] $*";  echo "${m}" >&2; echo "${m}" >> "${IMPORT_LOG}"; cleanup_mount; exit "${2:-1}"; }

log "Cinder USB Import Pipeline v${SCRIPT_VERSION} — import ID: ${IMPORT_ID}"

# ── List backups mode ─────────────────────────────────────────────────────────

if [[ "${OPT_LIST_BACKUPS}" == true ]]; then
    if [[ ! -d "${BACKUP_BASE_DIR}" ]]; then
        echo "No import backups found (${BACKUP_BASE_DIR} does not exist)."
        exit 0
    fi
    echo "Available USB import backups:"
    echo "────────────────────────────────────────"
    find "${BACKUP_BASE_DIR}" -maxdepth 1 -mindepth 1 -type d | sort -r | while read -r d; do
        local_id="$(basename "${d}")"
        manifest="${d}/manifest.txt"
        if [[ -f "${manifest}" ]]; then
            file_count="$(grep -c '^FILE:' "${manifest}" 2>/dev/null || echo '?')"
            echo "  ${local_id}  (${file_count} files)"
        else
            echo "  ${local_id}  (no manifest)"
        fi
    done
    exit 0
fi

# ── Rollback mode ─────────────────────────────────────────────────────────────

if [[ -n "${OPT_ROLLBACK}" ]]; then
    ROLLBACK_DIR="${BACKUP_BASE_DIR}/${OPT_ROLLBACK}"
    if [[ ! -d "${ROLLBACK_DIR}" ]]; then
        die "Rollback target not found: ${ROLLBACK_DIR}" 1
    fi
    log "=== ROLLBACK: restoring backup ${OPT_ROLLBACK} ==="
    perform_rollback "${ROLLBACK_DIR}" || die "Rollback failed. Manual intervention required." 6
    log "Rollback complete."
    exit 0
fi

# ── Dependency checks ─────────────────────────────────────────────────────────

for cmd in sha256sum lsblk mount umount find rsync; do
    if ! command -v "${cmd}" &>/dev/null; then
        die "Required command not found: ${cmd}. Install it with: apt install ${cmd}" 1
    fi
done

# ── Safety: check if server is running ───────────────────────────────────────

SERVER_PID_FILE="${CINDER_BASE_DIR}/logs/cinder.pid"

if [[ "${OPT_FORCE}" == false ]]; then
    if [[ -f "${SERVER_PID_FILE}" ]]; then
        SERVER_PID="$(cat "${SERVER_PID_FILE}")"
        if kill -0 "${SERVER_PID}" 2>/dev/null; then
            warn "Cinder Core is currently running (PID ${SERVER_PID})."
            warn "Importing files while the server is live may cause instability."
            warn "Stop the server first: systemctl stop cinder"
            warn "Or re-run with --force to bypass this check (at your own risk)."
            exit 1
        fi
    fi
fi

# ── Step 1: Detect USB device ─────────────────────────────────────────────────

log "Step 1: Detecting USB device..."

if [[ -n "${OPT_DEVICE}" ]]; then
    USB_DEVICE="${OPT_DEVICE}"
    log "Using explicitly specified device: ${USB_DEVICE}"
else
    # Auto-detect: find removable USB mass storage partitions.
    # lsblk output: NAME, TRAN (transport), RM (removable), TYPE
    USB_DEVICE=""
    while IFS= read -r line; do
        name="$(echo "${line}" | awk '{print $1}')"
        tran="$(echo "${line}" | awk '{print $2}')"
        rm_flag="$(echo "${line}" | awk '{print $3}')"
        type="$(echo "${line}" | awk '{print $4}')"
        if [[ "${tran}" == "usb" && "${rm_flag}" == "1" && "${type}" == "part" ]]; then
            USB_DEVICE="/dev/${name}"
            break
        fi
    done < <(lsblk -o NAME,TRAN,RM,TYPE -rn 2>/dev/null)

    if [[ -z "${USB_DEVICE}" ]]; then
        die "No USB mass storage device detected. Insert a USB drive and retry." 2
    fi

    log "Auto-detected USB device: ${USB_DEVICE}"
fi

if [[ ! -b "${USB_DEVICE}" ]]; then
    die "Device does not exist or is not a block device: ${USB_DEVICE}" 2
fi

# ── Step 2: Mount USB read-only ───────────────────────────────────────────────

log "Step 2: Mounting ${USB_DEVICE} read-only at ${USB_MOUNT}..."

mkdir -p "${USB_MOUNT}"

# Safety: ensure mount point is not already in use
if mountpoint -q "${USB_MOUNT}"; then
    warn "Mount point ${USB_MOUNT} is already in use. Unmounting first..."
    umount "${USB_MOUNT}" || die "Failed to unmount ${USB_MOUNT}" 3
fi

if ! mount -o ro,noexec,nosuid "${USB_DEVICE}" "${USB_MOUNT}" 2>>"${IMPORT_LOG}"; then
    die "Failed to mount ${USB_DEVICE}. Check filesystem compatibility (FAT32, ext4, exFAT)." 3
fi

log "Mounted successfully."

# Register cleanup trap after successful mount
cleanup_mount() {
    if mountpoint -q "${USB_MOUNT}" 2>/dev/null; then
        umount "${USB_MOUNT}" 2>/dev/null || warn "Could not unmount ${USB_MOUNT} — unmount manually."
        log "USB device unmounted."
    fi
}
trap cleanup_mount EXIT

# ── Step 3: Scan import directory ─────────────────────────────────────────────

log "Step 3: Scanning USB import directory..."

USB_IMPORT_DIR="${USB_MOUNT}/${USB_IMPORT_SUBDIR}"

if [[ ! -d "${USB_IMPORT_DIR}" ]]; then
    die "Expected import directory not found on USB: /${USB_IMPORT_SUBDIR}/
    Layout expected:
      /cinder-import/
        plugins/
        mods/
        datapacks/
        config/
        checksums.sha256" 4
fi

# Collect all importable files
declare -a IMPORT_FILES=()
while IFS= read -r f; do
    IMPORT_FILES+=( "${f}" )
done < <(find "${USB_IMPORT_DIR}" -type f | sort)

if [[ "${#IMPORT_FILES[@]}" -eq 0 ]]; then
    die "No files found in ${USB_IMPORT_DIR}. Nothing to import." 4
fi

log "Found ${#IMPORT_FILES[@]} file(s) to consider."

# ── Step 4: Validate ──────────────────────────────────────────────────────────

log "Step 4: Validating files..."

VALID_FILES=()
REJECTED_FILES=()

# Load checksums.sha256 from USB if present
CHECKSUMS_FILE="${USB_IMPORT_DIR}/checksums.sha256"
declare -A USB_CHECKSUMS=()

if [[ -f "${CHECKSUMS_FILE}" ]]; then
    log "Loading checksums from ${CHECKSUMS_FILE}..."
    while IFS= read -r line; do
        hash="$(echo "${line}" | awk '{print $1}')"
        fname="$(echo "${line}" | awk '{print $2}' | sed 's|^\./||')"
        USB_CHECKSUMS["${fname}"]="${hash}"
    done < "${CHECKSUMS_FILE}"
    log "Loaded ${#USB_CHECKSUMS[@]} checksum entries."
else
    warn "No checksums.sha256 found on USB. Checksum verification skipped."
    warn "Strongly recommended: generate checksums.sha256 on your source machine."
fi

# Load allowlist if configured
declare -A ALLOWLIST_HASHES=()
if [[ -n "${ALLOWLIST_FILE}" && -f "${ALLOWLIST_FILE}" ]]; then
    log "Loading allowlist from ${ALLOWLIST_FILE}..."
    while IFS= read -r line; do
        hash="$(echo "${line}" | awk '{print $1}')"
        ALLOWLIST_HASHES["${hash}"]=1
    done < "${ALLOWLIST_FILE}"
    log "Loaded ${#ALLOWLIST_HASHES[@]} allowlisted hashes."
elif [[ -n "${ALLOWLIST_FILE}" && ! -f "${ALLOWLIST_FILE}" ]]; then
    warn "Allowlist file configured but not found: ${ALLOWLIST_FILE}"
    warn "Proceeding without allowlist enforcement."
fi

for filepath in "${IMPORT_FILES[@]}"; do
    relpath="${filepath#"${USB_IMPORT_DIR}/"}"
    filename="$(basename "${filepath}")"
    ext="${filename##*.}"
    ext_lower="${ext,,}"
    reject_reason=""

    # Skip the checksums file itself
    if [[ "${filename}" == "checksums.sha256" ]]; then
        continue
    fi

    # Extension check
    ext_ok=false
    for allowed in "${ALLOWED_EXTENSIONS[@]}"; do
        if [[ "${ext_lower}" == "${allowed}" ]]; then
            ext_ok=true; break
        fi
    done
    if [[ "${ext_ok}" == false ]]; then
        reject_reason="disallowed extension: .${ext_lower}"
    fi

    # Size check
    if [[ -z "${reject_reason}" ]]; then
        filesize="$(stat -c%s "${filepath}")"
        if [[ "${filesize}" -gt "${MAX_FILE_SIZE_BYTES}" ]]; then
            reject_reason="file too large: ${filesize} bytes (max: ${MAX_FILE_SIZE_BYTES})"
        fi
    fi

    # Checksum verification (if checksums.sha256 was provided on USB)
    if [[ -z "${reject_reason}" && "${#USB_CHECKSUMS[@]}" -gt 0 ]]; then
        expected_hash="${USB_CHECKSUMS["${relpath}"]:-}"
        if [[ -z "${expected_hash}" ]]; then
            reject_reason="no checksum entry in checksums.sha256 for: ${relpath}"
        else
            actual_hash="$(sha256sum "${filepath}" | awk '{print $1}')"
            if [[ "${actual_hash}" != "${expected_hash}" ]]; then
                reject_reason="checksum mismatch (expected: ${expected_hash}, got: ${actual_hash})"
            fi
        fi
    fi

    # Allowlist check
    if [[ -z "${reject_reason}" && "${#ALLOWLIST_HASHES[@]}" -gt 0 ]]; then
        file_hash="$(sha256sum "${filepath}" | awk '{print $1}')"
        if [[ -z "${ALLOWLIST_HASHES["${file_hash}"]:-}" ]]; then
            reject_reason="hash not in allowlist: ${file_hash}"
        fi
    fi

    if [[ -n "${reject_reason}" ]]; then
        warn "REJECTED: ${relpath} — ${reject_reason}"
        REJECTED_FILES+=( "${relpath}" )
    else
        log "VALID:    ${relpath}"
        VALID_FILES+=( "${filepath}" )
    fi
done

if [[ "${#REJECTED_FILES[@]}" -gt 0 ]]; then
    warn "${#REJECTED_FILES[@]} file(s) failed validation. Aborting import — no files deployed."
    die "Fix validation errors and retry." 4
fi

if [[ "${#VALID_FILES[@]}" -eq 0 ]]; then
    die "No valid files to import after validation. Nothing to do." 4
fi

log "Validation passed: ${#VALID_FILES[@]} file(s) approved."

# ── Dry run exit ──────────────────────────────────────────────────────────────

if [[ "${OPT_DRY_RUN}" == true ]]; then
    log "=== DRY RUN: validation complete, no files deployed. ==="
    for f in "${VALID_FILES[@]}"; do
        log "  Would deploy: ${f#"${USB_IMPORT_DIR}/"}"
    done
    exit 0
fi

# ── Step 5: Backup current files ──────────────────────────────────────────────

log "Step 5: Creating pre-import backup (ID: ${IMPORT_ID})..."

BACKUP_DIR="${BACKUP_BASE_DIR}/${IMPORT_ID}"
mkdir -p "${BACKUP_DIR}"
BACKUP_MANIFEST="${BACKUP_DIR}/manifest.txt"

echo "IMPORT_ID: ${IMPORT_ID}" > "${BACKUP_MANIFEST}"
echo "TIMESTAMP: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "${BACKUP_MANIFEST}"
echo "USB_DEVICE: ${USB_DEVICE}" >> "${BACKUP_MANIFEST}"
echo "---" >> "${BACKUP_MANIFEST}"

backed_up=0

for filepath in "${VALID_FILES[@]}"; do
    relpath="${filepath#"${USB_IMPORT_DIR}/"}"
    subdir="$(dirname "${relpath}")"  # e.g. "plugins", "mods"

    # Determine the destination directory for this file category
    dest_dir="$(resolve_dest_dir "${subdir}")"
    dest_file="${dest_dir}/$(basename "${filepath}")"

    if [[ -f "${dest_file}" ]]; then
        # Back up the existing file
        backup_subdir="${BACKUP_DIR}/${subdir}"
        mkdir -p "${backup_subdir}"
        cp -p "${dest_file}" "${backup_subdir}/"
        echo "FILE: ${relpath} BACKED_UP: ${backup_subdir}/$(basename "${dest_file}")" >> "${BACKUP_MANIFEST}"
        backed_up=$(( backed_up + 1 ))
    else
        echo "FILE: ${relpath} BACKED_UP: (none — new file)" >> "${BACKUP_MANIFEST}"
    fi
done

log "Backed up ${backed_up} existing file(s) to ${BACKUP_DIR}."

# ── Step 6: Stage files ───────────────────────────────────────────────────────

log "Step 6: Staging files..."

mkdir -p "${STAGING_DIR}/${IMPORT_ID}"

for filepath in "${VALID_FILES[@]}"; do
    relpath="${filepath#"${USB_IMPORT_DIR}/"}"
    dest="${STAGING_DIR}/${IMPORT_ID}/${relpath}"
    mkdir -p "$(dirname "${dest}")"
    cp -p "${filepath}" "${dest}"
    log "  Staged: ${relpath}"
done

log "Staging complete."

# ── Step 7: Deploy ────────────────────────────────────────────────────────────

log "Step 7: Deploying files..."

deploy_ok=true

for filepath in "${VALID_FILES[@]}"; do
    relpath="${filepath#"${USB_IMPORT_DIR}/"}"
    subdir="$(echo "${relpath}" | cut -d'/' -f1)"
    dest_dir="$(resolve_dest_dir "${subdir}")"

    if [[ -z "${dest_dir}" ]]; then
        warn "Unknown destination category for: ${relpath} — skipped."
        continue
    fi

    mkdir -p "${dest_dir}"
    staged_file="${STAGING_DIR}/${IMPORT_ID}/${relpath}"
    dest_file="${dest_dir}/$(basename "${filepath}")"

    if ! cp -p "${staged_file}" "${dest_file}"; then
        warn "Failed to deploy: ${relpath} → ${dest_file}"
        deploy_ok=false
        break
    fi

    log "  Deployed: ${relpath} → ${dest_file}"
done

if [[ "${deploy_ok}" == false ]]; then
    warn "Deployment failed. Initiating automatic rollback from backup ${IMPORT_ID}..."
    if perform_rollback "${BACKUP_DIR}"; then
        warn "Rollback successful. Server files restored to pre-import state."
    else
        warn "ROLLBACK ALSO FAILED. Manual intervention required."
        warn "Backup location: ${BACKUP_DIR}"
        exit 6
    fi
    exit 5
fi

log "All files deployed successfully."

# ── Step 8: Cleanup ───────────────────────────────────────────────────────────

log "Step 8: Cleaning up..."

# Remove staging area for this import
rm -rf "${STAGING_DIR:?}/${IMPORT_ID}"

# Unmount USB (trap will also handle this, but explicit is cleaner)
cleanup_mount
trap - EXIT

# Write final manifest
echo "STATUS: SUCCESS" >> "${BACKUP_MANIFEST}"
echo "FILES_DEPLOYED: ${#VALID_FILES[@]}" >> "${BACKUP_MANIFEST}"

log "═══════════════════════════════════════════════════════"
log " USB Import complete."
log " Import ID:  ${IMPORT_ID}"
log " Files:      ${#VALID_FILES[@]} deployed"
log " Backup:     ${BACKUP_DIR}"
log " Log:        ${IMPORT_LOG}"
log " Restart Cinder: systemctl start cinder"
log "═══════════════════════════════════════════════════════"

exit 0

# ── Helper functions ──────────────────────────────────────────────────────────

resolve_dest_dir() {
    local category="$1"
    case "${category}" in
        plugins)   echo "${SERVER_PLUGINS_DIR}"   ;;
        mods)      echo "${SERVER_MODS_DIR}"       ;;
        datapacks) echo "${SERVER_DATAPACKS_DIR}"  ;;
        config)    echo "${SERVER_CONFIG_DIR}"     ;;
        *)         echo ""                         ;;
    esac
}

perform_rollback() {
    local backup_dir="$1"
    local manifest="${backup_dir}/manifest.txt"

    if [[ ! -f "${manifest}" ]]; then
        warn "Rollback: no manifest found in ${backup_dir}."
        return 1
    fi

    log "Rollback: restoring from ${backup_dir}..."

    while IFS= read -r line; do
        if [[ "${line}" =~ ^FILE:\ (.+)\ BACKED_UP:\ (.+)$ ]]; then
            relpath="${BASH_REMATCH[1]}"
            backed_up_path="${BASH_REMATCH[2]}"
            subdir="$(echo "${relpath}" | cut -d'/' -f1)"
            dest_dir="$(resolve_dest_dir "${subdir}")"
            dest_file="${dest_dir}/$(basename "${relpath}")"

            if [[ "${backed_up_path}" == "(none — new file)" ]]; then
                # File was new during import; remove it on rollback
                if [[ -f "${dest_file}" ]]; then
                    rm -f "${dest_file}"
                    log "  Rollback: removed new file ${dest_file}"
                fi
            else
                if [[ -f "${backed_up_path}" ]]; then
                    cp -p "${backed_up_path}" "${dest_file}"
                    log "  Rollback: restored ${dest_file}"
                else
                    warn "  Rollback: backup file missing: ${backed_up_path}"
                fi
            fi
        fi
    done < "${manifest}"

    echo "STATUS: ROLLED_BACK" >> "${manifest}"
    log "Rollback complete."
    return 0
}
