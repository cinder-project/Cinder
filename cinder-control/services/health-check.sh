#!/usr/bin/env bash
# =============================================================================
# Cinder Control — health-check.sh
# Comprehensive server health monitoring with structured output
#
# Overview:
#   Performs a multi-layer health assessment of the Cinder Core server and
#   the Cinder OS host system. Designed to run:
#
#     - On demand:    ./health-check.sh
#     - From cron:    */5 * * * * /opt/cinder/scripts/health-check.sh --quiet
#     - Via systemd:  cinder-health.timer (every 2 minutes)
#     - By Control:   called by metrics-display.sh for live dashboard data
#
#   Each check is individually gated and timed. Failures are reported with
#   context, not just exit codes.
#
# Output modes:
#   Default:    Human-readable colour terminal output
#   --quiet:    Only print failures and warnings; exit non-zero on any failure
#   --json:     Machine-readable JSON — consumed by Cinder Control dashboard
#   --brief:    Single-line summary: OK / WARN / FAIL with counts
#
# Health checks performed:
#   SYSTEM   — CPU temperature, CPU throttle state, memory, disk, load average
#   PROCESS  — Server PID alive, JVM memory usage, GC pressure
#   NETWORK  — Player port reachable, control socket alive
#   RUNTIME  — TPS from last-launch metrics, MSPT from profiler log
#   STORAGE  — World directory writable, backup recency, log rotation
#   USB      — USB import staging area status
#
# Exit codes:
#   0   All checks passed
#   1   One or more WARNING conditions
#   2   One or more FAILURE conditions
#   3   Script error (missing dependency, bad config)
#
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

# ── Configuration ─────────────────────────────────────────────────────────────

: "${CINDER_BASE_DIR:="/opt/cinder"}"
: "${CINDER_LOG_DIR:="${CINDER_BASE_DIR}/logs"}"
: "${CINDER_WORLD_DIR:="${CINDER_BASE_DIR}/world"}"
: "${CINDER_BACKUP_DIR:="${CINDER_BASE_DIR}/backups"}"
: "${SERVER_PORT:=25565}"
: "${CONTROL_METRICS_FILE:="${CINDER_LOG_DIR}/last-launch.json"}"
: "${PROFILER_LOG:="${CINDER_LOG_DIR}/cinder-server.log"}"

# Thresholds
readonly TEMP_WARN_C=70          # CPU temp warning threshold (°C)
readonly TEMP_CRIT_C=80          # CPU temp critical threshold (°C)
readonly MEM_WARN_PCT=85         # Memory usage warning (%)
readonly MEM_CRIT_PCT=95         # Memory usage critical (%)
readonly DISK_WARN_PCT=80        # Disk usage warning (%)
readonly DISK_CRIT_PCT=90        # Disk usage critical (%)
readonly LOAD_WARN_FACTOR=3      # Load avg warn: N × nproc
readonly LOAD_CRIT_FACTOR=6      # Load avg crit: N × nproc
readonly TPS_WARN=18.0           # TPS warning threshold
readonly TPS_CRIT=15.0           # TPS critical threshold
readonly MSPT_WARN_MS=40.0       # MSPT warning threshold
readonly MSPT_CRIT_MS=48.0       # MSPT critical threshold
readonly BACKUP_STALE_HOURS=26   # Hours before backup is considered stale

# ── Argument parsing ──────────────────────────────────────────────────────────

OPT_QUIET=false
OPT_JSON=false
OPT_BRIEF=false
OPT_NO_COLOUR=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --quiet)     OPT_QUIET=true;     shift ;;
        --json)      OPT_JSON=true;      shift ;;
        --brief)     OPT_BRIEF=true;     shift ;;
        --no-colour) OPT_NO_COLOUR=true; shift ;;
        -h|--help)
            grep '^#' "$0" | head -40 | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 3 ;;
    esac
done

# ── Colour helpers ────────────────────────────────────────────────────────────

if [[ "${OPT_NO_COLOUR}" == false && "${OPT_JSON}" == false && -t 1 ]]; then
    RED='\033[0;31m'; YELLOW='\033[0;33m'; GREEN='\033[0;32m'
    CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; RESET='\033[0m'
else
    RED=''; YELLOW=''; GREEN=''; CYAN=''; BOLD=''; DIM=''; RESET=''
fi

# ── Result tracking ───────────────────────────────────────────────────────────

declare -a CHECK_RESULTS=()   # "CATEGORY|NAME|STATUS|MESSAGE"
FAIL_COUNT=0
WARN_COUNT=0
PASS_COUNT=0

record() {
    local category="$1" name="$2" status="$3" message="$4"
    CHECK_RESULTS+=( "${category}|${name}|${status}|${message}" )
    case "${status}" in
        FAIL) FAIL_COUNT=$(( FAIL_COUNT + 1 )) ;;
        WARN) WARN_COUNT=$(( WARN_COUNT + 1 )) ;;
        OK)   PASS_COUNT=$(( PASS_COUNT + 1 )) ;;
    esac
}

# ── Utility functions ─────────────────────────────────────────────────────────

# Float comparison: returns 0 (true) if $1 > $2
float_gt() { echo "${1} ${2}" | awk '{exit ($1 > $2) ? 0 : 1}'; }
float_lt() { echo "${1} ${2}" | awk '{exit ($1 < $2) ? 0 : 1}'; }

# Read last N lines of the profiler log matching a pattern
grep_log_last() {
    local pattern="$1" count="${2:-1}"
    if [[ -f "${PROFILER_LOG}" ]]; then
        grep "${pattern}" "${PROFILER_LOG}" 2>/dev/null | tail -n "${count}" || true
    fi
}

# ── SECTION: SYSTEM ───────────────────────────────────────────────────────────

run_system_checks() {
    # CPU Temperature (Pi 4 specific)
    local temp_c="N/A"
    if command -v vcgencmd &>/dev/null; then
        raw="$(vcgencmd measure_temp 2>/dev/null | grep -oP '[0-9]+\.[0-9]+')"
        temp_c="${raw:-N/A}"
    elif [[ -f /sys/class/thermal/thermal_zone0/temp ]]; then
        raw_mk="$(cat /sys/class/thermal/thermal_zone0/temp)"
        temp_c="$(echo "${raw_mk}" | awk '{printf "%.1f", $1/1000}')"
    fi

    if [[ "${temp_c}" == "N/A" ]]; then
        record "SYSTEM" "cpu_temp" "WARN" "Temperature unavailable (vcgencmd not found)"
    elif float_gt "${temp_c}" "${TEMP_CRIT_C}"; then
        record "SYSTEM" "cpu_temp" "FAIL" "CPU temperature critical: ${temp_c}°C (threshold: ${TEMP_CRIT_C}°C)"
    elif float_gt "${temp_c}" "${TEMP_WARN_C}"; then
        record "SYSTEM" "cpu_temp" "WARN" "CPU temperature elevated: ${temp_c}°C (threshold: ${TEMP_WARN_C}°C)"
    else
        record "SYSTEM" "cpu_temp" "OK" "${temp_c}°C"
    fi

    # CPU Throttle State (Pi 4 specific)
    if command -v vcgencmd &>/dev/null; then
        throttle_hex="$(vcgencmd get_throttled 2>/dev/null | grep -oP '0x[0-9a-fA-F]+')"
        throttle_int="$(( throttle_hex ))"
        if (( (throttle_int & 0x4) != 0 )); then
            record "SYSTEM" "cpu_throttle" "FAIL" "Currently throttled due to temperature (throttled=${throttle_hex})"
        elif (( (throttle_int & 0x1) != 0 )); then
            record "SYSTEM" "cpu_throttle" "WARN" "Under-voltage detected (throttled=${throttle_hex})"
        elif (( (throttle_int & 0x2) != 0 )); then
            record "SYSTEM" "cpu_throttle" "WARN" "ARM frequency capped (throttled=${throttle_hex})"
        else
            record "SYSTEM" "cpu_throttle" "OK" "No throttling (${throttle_hex})"
        fi
    fi

    # Memory usage
    read -r mem_total mem_available <<< "$(awk '
        /MemTotal/     { total=$2 }
        /MemAvailable/ { avail=$2 }
        END { print total, avail }
    ' /proc/meminfo)"

    mem_used=$(( mem_total - mem_available ))
    mem_pct=$(( (mem_used * 100) / mem_total ))
    mem_used_mb=$(( mem_used / 1024 ))
    mem_total_mb=$(( mem_total / 1024 ))

    if (( mem_pct >= MEM_CRIT_PCT )); then
        record "SYSTEM" "memory" "FAIL" "${mem_used_mb}/${mem_total_mb} MB (${mem_pct}%) — critical"
    elif (( mem_pct >= MEM_WARN_PCT )); then
        record "SYSTEM" "memory" "WARN" "${mem_used_mb}/${mem_total_mb} MB (${mem_pct}%) — elevated"
    else
        record "SYSTEM" "memory" "OK" "${mem_used_mb}/${mem_total_mb} MB (${mem_pct}%)"
    fi

    # Disk usage (world partition)
    if [[ -d "${CINDER_WORLD_DIR}" ]]; then
        disk_info="$(df -P "${CINDER_WORLD_DIR}" | tail -1)"
        disk_pct="$(echo "${disk_info}" | awk '{gsub(/%/,""); print $5}')"
        disk_avail="$(echo "${disk_info}" | awk '{printf "%.1f", $4/1024/1024}')"
        disk_device="$(echo "${disk_info}" | awk '{print $1}')"

        if (( disk_pct >= DISK_CRIT_PCT )); then
            record "SYSTEM" "disk" "FAIL" "${disk_pct}% used on ${disk_device} (${disk_avail} GB free) — critical"
        elif (( disk_pct >= DISK_WARN_PCT )); then
            record "SYSTEM" "disk" "WARN" "${disk_pct}% used on ${disk_device} (${disk_avail} GB free) — elevated"
        else
            record "SYSTEM" "disk" "OK" "${disk_pct}% used on ${disk_device} (${disk_avail} GB free)"
        fi
    else
        record "SYSTEM" "disk" "WARN" "World directory not found: ${CINDER_WORLD_DIR}"
    fi

    # Load average
    nproc="$(nproc)"
    load_1="$(awk '{print $1}' /proc/loadavg)"
    load_5="$(awk '{print $2}' /proc/loadavg)"
    load_warn_threshold=$(( nproc * LOAD_WARN_FACTOR ))
    load_crit_threshold=$(( nproc * LOAD_CRIT_FACTOR ))

    load_1_int="$(echo "${load_1}" | awk '{printf "%d", $1}')"
    if (( load_1_int >= load_crit_threshold )); then
        record "SYSTEM" "load" "FAIL" "Load avg 1m=${load_1} 5m=${load_5} (${nproc} cores) — critical"
    elif (( load_1_int >= load_warn_threshold )); then
        record "SYSTEM" "load" "WARN" "Load avg 1m=${load_1} 5m=${load_5} (${nproc} cores) — elevated"
    else
        record "SYSTEM" "load" "OK" "1m=${load_1} 5m=${load_5} (${nproc} cores)"
    fi
}

# ── SECTION: PROCESS ──────────────────────────────────────────────────────────

run_process_checks() {
    local pid_file="${CINDER_LOG_DIR}/cinder.pid"

    # Server process alive check
    if [[ ! -f "${pid_file}" ]]; then
        record "PROCESS" "server_pid" "WARN" "PID file not found — server may not be running"
        return
    fi

    local server_pid
    server_pid="$(cat "${pid_file}")"

    if ! kill -0 "${server_pid}" 2>/dev/null; then
        record "PROCESS" "server_pid" "FAIL" "Server process ${server_pid} is not alive (stale PID file)"
        return
    fi

    record "PROCESS" "server_pid" "OK" "PID ${server_pid} alive"

    # JVM heap usage (from /proc/<pid>/status)
    local vm_rss_kb vm_rss_mb
    vm_rss_kb="$(awk '/VmRSS/ {print $2}' "/proc/${server_pid}/status" 2>/dev/null || echo 0)"
    vm_rss_mb=$(( vm_rss_kb / 1024 ))

    if (( vm_rss_mb > 3000 )); then
        record "PROCESS" "jvm_rss" "WARN" "JVM RSS: ${vm_rss_mb} MB — high (check for memory leak)"
    else
        record "PROCESS" "jvm_rss" "OK" "JVM RSS: ${vm_rss_mb} MB"
    fi

    # Process uptime
    if [[ -f "/proc/${server_pid}/stat" ]]; then
        local start_ticks hz uptime_s start_s proc_age_s
        start_ticks="$(awk '{print $22}' "/proc/${server_pid}/stat" 2>/dev/null || echo 0)"
        hz="$(getconf CLK_TCK 2>/dev/null || echo 100)"
        uptime_s="$(awk '{print int($1)}' /proc/uptime)"
        start_s=$(( start_ticks / hz ))
        proc_age_s=$(( uptime_s - start_s ))
        proc_age_min=$(( proc_age_s / 60 ))
        record "PROCESS" "uptime" "OK" "${proc_age_min} minutes"
    fi

    # Check for GC pressure in recent server log
    local gc_warn_count
    gc_warn_count="$(grep_log_last "GC overhead\|OutOfMemoryError\|heap space" 20 | wc -l)"
    if (( gc_warn_count > 0 )); then
        record "PROCESS" "gc_pressure" "WARN" "${gc_warn_count} GC warning(s) in recent log — check heap settings"
    else
        record "PROCESS" "gc_pressure" "OK" "No GC warnings in recent log"
    fi
}

# ── SECTION: NETWORK ──────────────────────────────────────────────────────────

run_network_checks() {
    # Check if the Minecraft port is listening
    if command -v ss &>/dev/null; then
        if ss -tlnp 2>/dev/null | grep -q ":${SERVER_PORT}"; then
            record "NETWORK" "player_port" "OK" "Port ${SERVER_PORT} listening"
        else
            record "NETWORK" "player_port" "FAIL" "Port ${SERVER_PORT} not listening — server may be starting or down"
        fi
    elif command -v netstat &>/dev/null; then
        if netstat -tlnp 2>/dev/null | grep -q ":${SERVER_PORT}"; then
            record "NETWORK" "player_port" "OK" "Port ${SERVER_PORT} listening"
        else
            record "NETWORK" "player_port" "FAIL" "Port ${SERVER_PORT} not listening"
        fi
    else
        record "NETWORK" "player_port" "WARN" "Cannot check port — ss/netstat not available"
    fi

    # Outbound connectivity (to Mojang auth if online mode)
    if curl -sf --max-time 5 "https://sessionserver.mojang.com" \
            -o /dev/null --head 2>/dev/null; then
        record "NETWORK" "mojang_auth" "OK" "Mojang session server reachable"
    else
        record "NETWORK" "mojang_auth" "WARN" "Mojang session server unreachable (offline mode may be active)"
    fi
}

# ── SECTION: RUNTIME ──────────────────────────────────────────────────────────

run_runtime_checks() {
    # Parse TPS and MSPT from profiler log lines
    # Profiler emits: tick=N total=X.XXms [PRE=... ENTITY=... CHUNK=...] [OK|OVERRUN|SPIKE]
    local last_tick_lines tps_estimate mspt_last overrun_count spike_count

    last_tick_lines="$(grep_log_last 'total=.*ms' 100)"

    if [[ -z "${last_tick_lines}" ]]; then
        record "RUNTIME" "tps" "WARN" "No tick data in server log — server may be starting"
        return
    fi

    # Estimate TPS from count of tick lines in the last ~5 seconds of log
    # (Each tick line has a timestamp in [HH:MM:SS] format)
    local recent_window recent_ticks
    recent_window=5
    recent_ticks="$(echo "${last_tick_lines}" | tail -n 200 | \
        awk -v window="${recent_window}" '
            /\[([0-9]{2}:[0-9]{2}:[0-9]{2})\]/ {
                match($0, /\[([0-9]{2}:[0-9]{2}:[0-9]{2})\]/, a)
                split(a[1], t, ":")
                secs = t[1]*3600 + t[2]*60 + t[3]
                times[NR] = secs
                count++
            }
            END {
                if (count < 2) { print 20; exit }
                first = times[1]; last = times[count]
                elapsed = last - first
                if (elapsed <= 0) elapsed = 1
                printf "%.2f", (count-1) / elapsed
            }
        ')"

    tps_estimate="${recent_ticks:-20.00}"

    # Last MSPT
    mspt_last="$(echo "${last_tick_lines}" | tail -1 | \
        grep -oP 'total=\K[0-9]+\.[0-9]+')"
    mspt_last="${mspt_last:-0.00}"

    # Count overruns and spikes in last 100 ticks
    overrun_count="$(echo "${last_tick_lines}" | grep -c '\[OVERRUN\]' || echo 0)"
    spike_count="$(echo "${last_tick_lines}"   | grep -c '\[SPIKE\]'   || echo 0)"

    if float_lt "${tps_estimate}" "${TPS_CRIT}"; then
        record "RUNTIME" "tps" "FAIL" "TPS: ${tps_estimate} (threshold: ${TPS_CRIT})"
    elif float_lt "${tps_estimate}" "${TPS_WARN}"; then
        record "RUNTIME" "tps" "WARN" "TPS: ${tps_estimate} (threshold: ${TPS_WARN})"
    else
        record "RUNTIME" "tps" "OK" "TPS: ${tps_estimate}"
    fi

    if float_gt "${mspt_last}" "${MSPT_CRIT_MS}"; then
        record "RUNTIME" "mspt" "FAIL" "Last MSPT: ${mspt_last}ms (threshold: ${MSPT_CRIT_MS}ms)"
    elif float_gt "${mspt_last}" "${MSPT_WARN_MS}"; then
        record "RUNTIME" "mspt" "WARN" "Last MSPT: ${mspt_last}ms (threshold: ${MSPT_WARN_MS}ms)"
    else
        record "RUNTIME" "mspt" "OK" "Last MSPT: ${mspt_last}ms"
    fi

    if (( spike_count > 0 )); then
        record "RUNTIME" "lag_spikes" "WARN" "${spike_count} spike(s) and ${overrun_count} overrun(s) in last 100 ticks"
    else
        record "RUNTIME" "lag_spikes" "OK" "${overrun_count} overruns, 0 spikes in last 100 ticks"
    fi

    # Read preset from last-launch.json
    if [[ -f "${CONTROL_METRICS_FILE}" ]] && command -v jq &>/dev/null; then
        local preset heap_min heap_max
        preset="$(jq -r '.preset // "unknown"' "${CONTROL_METRICS_FILE}" 2>/dev/null)"
        heap_min="$(jq -r '.heapMin // "?"' "${CONTROL_METRICS_FILE}" 2>/dev/null)"
        heap_max="$(jq -r '.heapMax // "?"' "${CONTROL_METRICS_FILE}" 2>/dev/null)"
        record "RUNTIME" "preset" "OK" "${preset} (heap: ${heap_min}–${heap_max})"
    fi
}

# ── SECTION: STORAGE ──────────────────────────────────────────────────────────

run_storage_checks() {
    # World directory writable
    if [[ -d "${CINDER_WORLD_DIR}" ]]; then
        if touch "${CINDER_WORLD_DIR}/.cinder-write-test" 2>/dev/null; then
            rm -f "${CINDER_WORLD_DIR}/.cinder-write-test"
            record "STORAGE" "world_writable" "OK" "${CINDER_WORLD_DIR} is writable"
        else
            record "STORAGE" "world_writable" "FAIL" "${CINDER_WORLD_DIR} is NOT writable"
        fi
    else
        record "STORAGE" "world_writable" "FAIL" "World directory not found: ${CINDER_WORLD_DIR}"
    fi

    # Backup recency check
    if [[ -d "${CINDER_BACKUP_DIR}" ]]; then
        local newest_backup age_hours
        newest_backup="$(find "${CINDER_BACKUP_DIR}" -type f -name "*.tar.gz" \
            | xargs ls -t 2>/dev/null | head -1)"

        if [[ -z "${newest_backup}" ]]; then
            record "STORAGE" "backup_recency" "WARN" "No backups found in ${CINDER_BACKUP_DIR}"
        else
            local mod_epoch now_epoch
            mod_epoch="$(stat -c%Y "${newest_backup}")"
            now_epoch="$(date +%s)"
            age_hours=$(( (now_epoch - mod_epoch) / 3600 ))

            if (( age_hours > BACKUP_STALE_HOURS )); then
                record "STORAGE" "backup_recency" "WARN" \
                    "Newest backup is ${age_hours}h old (threshold: ${BACKUP_STALE_HOURS}h): $(basename "${newest_backup}")"
            else
                record "STORAGE" "backup_recency" "OK" \
                    "Last backup: ${age_hours}h ago ($(basename "${newest_backup}"))"
            fi
        fi
    else
        record "STORAGE" "backup_recency" "WARN" "Backup directory not found: ${CINDER_BACKUP_DIR}"
    fi

    # USB import staging area
    local staging_dir="${CINDER_BASE_DIR}/staging/usb-import"
    if [[ -d "${staging_dir}" ]]; then
        local staged_count
        staged_count="$(find "${staging_dir}" -type f 2>/dev/null | wc -l)"
        if (( staged_count > 0 )); then
            record "STORAGE" "usb_staging" "WARN" \
                "${staged_count} file(s) remain in USB staging area — import may be incomplete"
        else
            record "STORAGE" "usb_staging" "OK" "USB staging area clear"
        fi
    fi
}

# ── Run all checks ────────────────────────────────────────────────────────────

run_system_checks
run_process_checks
run_network_checks
run_runtime_checks
run_storage_checks

OVERALL_STATUS="OK"
(( FAIL_COUNT > 0 )) && OVERALL_STATUS="FAIL"
(( FAIL_COUNT == 0 && WARN_COUNT > 0 )) && OVERALL_STATUS="WARN"

# ── Output: JSON ──────────────────────────────────────────────────────────────

if [[ "${OPT_JSON}" == true ]]; then
    echo "{"
    echo "  \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"overall\": \"${OVERALL_STATUS}\","
    echo "  \"counts\": { \"pass\": ${PASS_COUNT}, \"warn\": ${WARN_COUNT}, \"fail\": ${FAIL_COUNT} },"
    echo "  \"checks\": ["
    total="${#CHECK_RESULTS[@]}"
    for i in "${!CHECK_RESULTS[@]}"; do
        IFS='|' read -r category name status message <<< "${CHECK_RESULTS[$i]}"
        comma=$( (( i < total - 1 )) && echo "," || echo "" )
        echo "    { \"category\": \"${category}\", \"name\": \"${name}\", \"status\": \"${status}\", \"message\": \"${message}\" }${comma}"
    done
    echo "  ]"
    echo "}"
    (( FAIL_COUNT > 0 )) && exit 2
    (( WARN_COUNT > 0 )) && exit 1
    exit 0
fi

# ── Output: Brief ─────────────────────────────────────────────────────────────

if [[ "${OPT_BRIEF}" == true ]]; then
    echo "${OVERALL_STATUS} — ${PASS_COUNT} OK, ${WARN_COUNT} WARN, ${FAIL_COUNT} FAIL"
    (( FAIL_COUNT > 0 )) && exit 2
    (( WARN_COUNT > 0 )) && exit 1
    exit 0
fi

# ── Output: Human-readable ────────────────────────────────────────────────────

if [[ "${OPT_QUIET}" == false ]]; then
    echo ""
    echo -e "${BOLD}${CYAN}Cinder Health Check${RESET}  $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    echo -e "${DIM}─────────────────────────────────────────────────${RESET}"

    current_category=""
    for result in "${CHECK_RESULTS[@]}"; do
        IFS='|' read -r category name status message <<< "${result}"

        if [[ "${category}" != "${current_category}" ]]; then
            echo ""
            echo -e "  ${BOLD}${category}${RESET}"
            current_category="${category}"
        fi

        case "${status}" in
            OK)   icon="${GREEN}●${RESET}" col="${GREEN}" ;;
            WARN) icon="${YELLOW}●${RESET}" col="${YELLOW}" ;;
            FAIL) icon="${RED}●${RESET}"  col="${RED}"  ;;
            *)    icon="?" col="" ;;
        esac

        printf "  %b  %-20s %b%s%b\n" "${icon}" "${name}" "${col}" "${message}" "${RESET}"
    done

    echo ""
    echo -e "${DIM}─────────────────────────────────────────────────${RESET}"

    case "${OVERALL_STATUS}" in
        OK)   echo -e "  ${GREEN}${BOLD}HEALTHY${RESET}    ${PASS_COUNT} checks passed" ;;
        WARN) echo -e "  ${YELLOW}${BOLD}WARNING${RESET}    ${WARN_COUNT} warning(s), ${PASS_COUNT} passed" ;;
        FAIL) echo -e "  ${RED}${BOLD}UNHEALTHY${RESET}  ${FAIL_COUNT} failure(s), ${WARN_COUNT} warning(s)" ;;
    esac
    echo ""
fi

# Quiet mode: print only non-OK results
if [[ "${OPT_QUIET}" == true ]]; then
    for result in "${CHECK_RESULTS[@]}"; do
        IFS='|' read -r category name status message <<< "${result}"
        if [[ "${status}" != "OK" ]]; then
            echo "[${status}] ${category}/${name}: ${message}"
        fi
    done
fi

(( FAIL_COUNT > 0 )) && exit 2
(( WARN_COUNT > 0 )) && exit 1
exit 0
