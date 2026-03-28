#!/usr/bin/env bash
# =============================================================================
# Cinder OS — prestart-check.sh
# Pre-start environment validation for cinder.service (ExecStartPre)
#
# Called by systemd as root before launch.sh runs. Validates that the
# environment is ready for Cinder Core to start. Exits non-zero on any
# failure, which prevents systemd from proceeding to ExecStart.
#
# Checks performed:
#   1. Java 21 is present and is the correct version
#   2. Cinder JAR exists and is readable
#   3. Required directories exist with correct ownership
#   4. Sufficient disk space on world and log volumes
#   5. Sufficient free memory for the configured heap
#   6. CPU governor set to performance for benchmark/extreme presets
#   7. System clock is synchronised (advisory — non-fatal)
#
# Exit codes:
#   0   All checks passed — systemd proceeds to ExecStart
#   1   Fatal check failed — systemd aborts start, marks unit failed
#
# Design notes:
#   - All output goes to stdout/stderr, captured by journald
#   - Each check logs a one-line result; failures log a remediation hint
#   - The script is intentionally fast (< 2 seconds) to keep startup latency low
#   - Non-fatal checks log warnings but do not set FAILED
#   - The script is safe to run manually for diagnostics
# =============================================================================

set -uo pipefail
IFS=$'\n\t'

# ── Configuration ─────────────────────────────────────────────────────────────

: "${CINDER_BASE_DIR:=/opt/cinder}"
: "${CINDER_JAR:=${CINDER_BASE_DIR}/cinder-core/build/libs/cinder-core.jar}"
: "${CINDER_WORLD_DIR:=${CINDER_BASE_DIR}/world}"
: "${CINDER_LOG_DIR:=${CINDER_BASE_DIR}/logs}"
: "${CINDER_PRESET:=survival}"
: "${JAVA_HOME:=/usr/lib/jvm/java-21-openjdk-arm64}"

readonly JAVA_BIN="${JAVA_HOME}/bin/java"
readonly CINDER_USER="cinder"

readonly MIN_DISK_WORLD_MB=512
readonly MIN_DISK_LOGS_MB=128
readonly MIN_FREE_MEM_MB=768

readonly PERFORMANCE_PRESETS=("benchmark" "extreme")
readonly CPU_GOVERNOR_PATH="/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"

# ── Output helpers ────────────────────────────────────────────────────────────

FAILED=0

_pass()  { echo "[prestart] OK      $*"; }
_warn()  { echo "[prestart] WARN    $*"; }
_fail()  { echo "[prestart] FAIL    $*" >&2; FAILED=1; }
_hint()  { echo "[prestart]         → $*" >&2; }
_info()  { echo "[prestart] INFO    $*"; }

# ── Check 1: Java 21 ──────────────────────────────────────────────────────────

check_java() {
    if [[ ! -x "${JAVA_BIN}" ]]; then
        _fail "Java not found at ${JAVA_BIN}"
        _hint "Install: apt install openjdk-21-jre-headless"
        return
    fi

    local version_output
    version_output=$("${JAVA_BIN}" -version 2>&1 | head -1)

    if echo "${version_output}" | grep -q '"21\.'; then
        _pass "Java 21 — ${version_output}"
    else
        _fail "Java 21 required, found: ${version_output}"
        _hint "Set JAVA_HOME to a Java 21 installation"
    fi
}

# ── Check 2: Cinder JAR ───────────────────────────────────────────────────────

check_jar() {
    if [[ ! -f "${CINDER_JAR}" ]]; then
        _fail "Cinder JAR not found: ${CINDER_JAR}"
        _hint "Build Cinder Core or deploy the JAR to ${CINDER_JAR}"
        return
    fi

    if [[ ! -r "${CINDER_JAR}" ]]; then
        _fail "Cinder JAR not readable: ${CINDER_JAR}"
        _hint "chown cinder:cinder ${CINDER_JAR} && chmod 644 ${CINDER_JAR}"
        return
    fi

    local jar_size_kb
    jar_size_kb=$(du -k "${CINDER_JAR}" | cut -f1)

    if [[ "${jar_size_kb}" -lt 10 ]]; then
        _fail "Cinder JAR suspiciously small (${jar_size_kb} KB) — may be corrupt"
        return
    fi

    _pass "Cinder JAR — ${CINDER_JAR} (${jar_size_kb} KB)"
}

# ── Check 3: Required directories ────────────────────────────────────────────

check_directories() {
    local dirs=(
        "${CINDER_BASE_DIR}"
        "${CINDER_WORLD_DIR}"
        "${CINDER_LOG_DIR}"
        "${CINDER_BASE_DIR}/backups"
        "${CINDER_BASE_DIR}/staging"
        "${CINDER_BASE_DIR}/config"
    )

    local all_ok=true

    for dir in "${dirs[@]}"; do
        if [[ ! -d "${dir}" ]]; then
            _fail "Required directory missing: ${dir}"
            _hint "mkdir -p ${dir} && chown -R ${CINDER_USER}:${CINDER_USER} ${dir}"
            all_ok=false
            continue
        fi

        local owner
        owner=$(stat -c '%U' "${dir}" 2>/dev/null || echo "unknown")

        if [[ "${owner}" != "${CINDER_USER}" && "${owner}" != "root" ]]; then
            _fail "Directory ${dir} owned by '${owner}', expected '${CINDER_USER}'"
            _hint "chown -R ${CINDER_USER}:${CINDER_USER} ${dir}"
            all_ok=false
        fi
    done

    if [[ "${all_ok}" == true ]]; then
        _pass "Required directories present"
    fi
}

# ── Check 4: Disk space ───────────────────────────────────────────────────────

check_disk_space() {
    _check_volume() {
        local path="$1"
        local min_mb="$2"
        local label="$3"

        if [[ ! -d "${path}" ]]; then
            return
        fi

        local free_mb
        free_mb=$(df -m "${path}" 2>/dev/null | awk 'NR==2 {print $4}')

        if [[ -z "${free_mb}" ]]; then
            _warn "Could not determine free space for ${label} (${path})"
            return
        fi

        if [[ "${free_mb}" -lt "${min_mb}" ]]; then
            _fail "Insufficient disk space for ${label}: ${free_mb} MB free (min: ${min_mb} MB)"
            _hint "Free up space at ${path} or reduce CINDER_BACKUP_RETAIN"
        else
            _pass "Disk space ${label}: ${free_mb} MB free"
        fi
    }

    _check_volume "${CINDER_WORLD_DIR}" "${MIN_DISK_WORLD_MB}" "world"
    _check_volume "${CINDER_LOG_DIR}"   "${MIN_DISK_LOGS_MB}"  "logs"
}

# ── Check 5: Available memory ─────────────────────────────────────────────────

check_memory() {
    local free_mb
    free_mb=$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)

    if [[ -z "${free_mb}" ]]; then
        _warn "Could not determine available memory"
        return
    fi

    if [[ "${free_mb}" -lt "${MIN_FREE_MEM_MB}" ]]; then
        _fail "Insufficient free memory: ${free_mb} MB available (min: ${MIN_FREE_MEM_MB} MB)"
        _hint "Stop other services or reduce heap via CINDER_HEAP_MAX"
    else
        _pass "Available memory: ${free_mb} MB"
    fi
}

# ── Check 6: CPU governor (performance presets only) ─────────────────────────

check_cpu_governor() {
    local is_performance_preset=false
    for p in "${PERFORMANCE_PRESETS[@]}"; do
        if [[ "${CINDER_PRESET}" == "${p}" ]]; then
            is_performance_preset=true
            break
        fi
    done

    if [[ "${is_performance_preset}" == false ]]; then
        return
    fi

    if [[ ! -f "${CPU_GOVERNOR_PATH}" ]]; then
        _warn "CPU governor path not found (${CPU_GOVERNOR_PATH}) — skipping governor check"
        return
    fi

    local current_governor
    current_governor=$(cat "${CPU_GOVERNOR_PATH}" 2>/dev/null || echo "unknown")

    if [[ "${current_governor}" == "performance" ]]; then
        _pass "CPU governor: performance (preset=${CINDER_PRESET})"
        return
    fi

    _info "Setting CPU governor to performance for preset '${CINDER_PRESET}'"

    local cpu_path
    for cpu_path in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
        [[ -f "${cpu_path}" ]] && echo "performance" > "${cpu_path}" 2>/dev/null || true
    done

    local new_governor
    new_governor=$(cat "${CPU_GOVERNOR_PATH}" 2>/dev/null || echo "unknown")

    if [[ "${new_governor}" == "performance" ]]; then
        _pass "CPU governor set to performance (was: ${current_governor})"
    else
        _warn "Could not set CPU governor to performance (current: ${new_governor})"
        _hint "Manually: echo performance | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor"
    fi
}

# ── Check 7: Clock sync (advisory) ───────────────────────────────────────────

check_clock_sync() {
    if command -v timedatectl >/dev/null 2>&1; then
        if timedatectl status 2>/dev/null | grep -q "synchronized: yes"; then
            _pass "System clock synchronised"
        else
            _warn "System clock may not be synchronised"
            _hint "systemctl enable --now systemd-timesyncd"
        fi
    else
        _warn "timedatectl not available — cannot verify clock sync"
    fi
}

# ── Run all checks ────────────────────────────────────────────────────────────

_info "Cinder pre-start check — preset=${CINDER_PRESET}"
_info "Base: ${CINDER_BASE_DIR}  JAR: ${CINDER_JAR}"

check_java
check_jar
check_directories
check_disk_space
check_memory
check_cpu_governor
check_clock_sync

if [[ "${FAILED}" -ne 0 ]]; then
    echo "[prestart] FAILED — one or more checks failed. Cinder will not start." >&2
    echo "[prestart] Run this script manually to see full diagnostics:" >&2
    echo "[prestart]   sudo /opt/cinder/scripts/prestart-check.sh" >&2
    exit 1
fi

_pass "All checks passed. Starting Cinder Core."
exit 0
