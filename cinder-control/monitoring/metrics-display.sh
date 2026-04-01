#!/usr/bin/env bash
# =============================================================================
# Cinder Control — metrics-display.sh
# Live TPS/MSPT terminal dashboard with rolling graphs
#
# Overview:
#   A self-refreshing terminal dashboard for monitoring Cinder Core in real
#   time. Renders a full-screen view showing:
#
#     - Server status header (preset, uptime, PID)
#     - Live TPS gauge with colour coding
#     - Rolling MSPT sparkline graph (last 60 seconds)
#     - Per-phase MSPT breakdown (PRE / WORLD / ENTITY / CHUNK / POST)
#     - Entity pipeline stats (critical / standard / deferred counts)
#     - System metrics (CPU temp, memory, disk)
#     - Recent lag spikes and overruns
#     - Last 5 health check results
#
#   The dashboard reads data from:
#     - Server log (tick lines emitted by TickProfiler)
#     - last-launch.json (preset metadata)
#     - health-check.sh --json (system health data)
#     - /proc filesystem (live system stats)
#
# Usage:
#   ./metrics-display.sh [--interval <seconds>] [--no-health] [--compact]
#
#   --interval <n>   Refresh interval in seconds (default: 2)
#   --no-health      Skip health check integration (faster refresh)
#   --compact        Reduced height mode for 24-line terminals
#   --log <file>     Use a specific log file instead of the default
#
# Requirements:
#   - A terminal with at least 80x30 characters
#   - bash 4.3+
#   - tput (from ncurses)
#   - jq (for JSON parsing of last-launch.json)
#   - Cinder server must be running and emitting tick log lines
#
# Exit:
#   Ctrl+C to exit cleanly (terminal state is restored on exit)
#
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

# ── Configuration ─────────────────────────────────────────────────────────────

: "${CINDER_BASE_DIR:="/opt/cinder"}"
: "${CINDER_LOG_DIR:="${CINDER_BASE_DIR}/logs"}"
: "${SERVER_LOG:="${CINDER_LOG_DIR}/cinder-server.log"}"
: "${LAUNCH_META:="${CINDER_LOG_DIR}/last-launch.json"}"
: "${HEALTH_CHECK:="${CINDER_BASE_DIR}/scripts/health-check.sh"}"
: "${PID_FILE:="${CINDER_LOG_DIR}/cinder.pid"}"
: "${CINDER_AGGREGATE_FILE:="${CINDER_BASE_DIR}/metrics/aggregate-latest.json"}"

# ── Argument parsing ──────────────────────────────────────────────────────────

OPT_INTERVAL=2
OPT_NO_HEALTH=false
OPT_COMPACT=false
OPT_AGGREGATE_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --interval) OPT_INTERVAL="$2"; shift 2 ;;
        --no-health) OPT_NO_HEALTH=true; shift ;;
        --compact)   OPT_COMPACT=true;  shift ;;
        --log)       SERVER_LOG="$2";   shift 2 ;;
        --aggregate-file) OPT_AGGREGATE_FILE="$2"; shift 2 ;;
        -h|--help)
            grep '^#' "$0" | head -40 | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "${OPT_AGGREGATE_FILE}" ]]; then
    OPT_AGGREGATE_FILE="${CINDER_AGGREGATE_FILE}"
fi

# ── Terminal setup ────────────────────────────────────────────────────────────

if ! command -v tput &>/dev/null; then
    echo "tput not found. Install ncurses: apt install ncurses-bin" >&2
    exit 1
fi

# Colour codes
R='\033[0;31m'; Y='\033[0;33m'; G='\033[0;32m'
C='\033[0;36m'; M='\033[0;35m'; B='\033[0;34m'
W='\033[1;37m'; DIM='\033[2m';  RST='\033[0m'
BOLD='\033[1m'; BLINK='\033[5m'

# TPS colour thresholds
tps_colour() {
    local tps="$1"
    echo "${tps}" | awk -v r="${R}" -v y="${Y}" -v g="${G}" '
        { if ($1+0 < 15) print r
          else if ($1+0 < 18) print y
          else print g }'
}

# MSPT colour thresholds
mspt_colour() {
    local ms="$1"
    echo "${ms}" | awk -v r="${R}" -v y="${Y}" -v g="${G}" '
        { if ($1+0 > 48) print r
          else if ($1+0 > 40) print y
          else print g }'
}

# Sparkline character set (braille-style blocks)
# Maps normalised values 0-7 to Unicode block elements
SPARK_CHARS=(' ' '▁' '▂' '▃' '▄' '▅' '▆' '▇' '█')

build_sparkline() {
    # $1: space-separated list of float values
    # $2: display width in chars
    local values="$1" width="${2:-60}"
    local -a arr=()
    read -ra arr <<< "${values}"

    # Trim to display width
    local count="${#arr[@]}"
    local start=0
    if (( count > width )); then start=$(( count - width )); fi

    # Find max for normalisation
    local max=50  # floor at 50ms for consistent scale
    for v in "${arr[@]:${start}}"; do
        v_int="${v%%.*}"
        (( v_int > max )) && max="${v_int}"
    done

    local line=""
    for v in "${arr[@]:${start}}"; do
        local idx
        idx=$(echo "${v} ${max}" | awk '{
            pct = ($1 / $2)
            idx = int(pct * 8)
            if (idx > 8) idx = 8
            if (idx < 0) idx = 0
            print idx
        }')
        line+="${SPARK_CHARS[${idx}]}"
    done

    echo "${line}"
}

# Progress bar (fill/empty)
progress_bar() {
    local value="$1" max="$2" width="${3:-20}" fill_char="${4:-█}" empty_char="${5:-░}"
    local filled
    filled=$(echo "${value} ${max} ${width}" | awk '{
        n = int(($1/$2) * $3)
        if (n > $3) n = $3
        if (n < 0) n = 0
        print n
    }')
    local empty=$(( width - filled ))
    printf '%0.s'"${fill_char}" $(seq 1 "${filled}" 2>/dev/null || echo "")
    printf '%0.s'"${empty_char}" $(seq 1 "${empty}" 2>/dev/null || echo "")
}

# ── State ─────────────────────────────────────────────────────────────────────

declare -a MSPT_HISTORY=()        # Rolling MSPT values (last 60)
declare -a TPS_HISTORY=()         # Rolling TPS estimates
MAX_HISTORY=60

# Cached values (updated each refresh)
LAST_TPS="--"
LAST_MSPT="--"
LAST_TEMP="--"
LAST_PRESET="--"
LAST_HEAP_MIN="--"
LAST_HEAP_MAX="--"
LAST_PID="--"
LAST_UPTIME_MIN=0
LAST_ENTITY_CRIT=0
LAST_ENTITY_STD=0
LAST_ENTITY_DEF=0
PHASE_PRE="--"
PHASE_WORLD="--"
PHASE_ENTITY="--"
PHASE_CHUNK="--"
PHASE_POST="--"
HEALTH_SUMMARY="--"
AGGREGATE_SUMMARY="--"
declare -a RECENT_SPIKES=()

# ── Data collection ───────────────────────────────────────────────────────────

collect_launch_meta() {
    if [[ -f "${LAUNCH_META}" ]] && command -v jq &>/dev/null; then
        LAST_PRESET="$(jq -r '.preset // "--"' "${LAUNCH_META}" 2>/dev/null)"
        LAST_HEAP_MIN="$(jq -r '.heapMin // "--"' "${LAUNCH_META}" 2>/dev/null)"
        LAST_HEAP_MAX="$(jq -r '.heapMax // "--"' "${LAUNCH_META}" 2>/dev/null)"
    fi
}

collect_pid_info() {
    if [[ -f "${PID_FILE}" ]]; then
        LAST_PID="$(cat "${PID_FILE}")"
        if kill -0 "${LAST_PID}" 2>/dev/null; then
            # Uptime from /proc
            local start_ticks hz uptime_s start_s
            start_ticks="$(awk '{print $22}' "/proc/${LAST_PID}/stat" 2>/dev/null || echo 0)"
            hz="$(getconf CLK_TCK 2>/dev/null || echo 100)"
            uptime_s="$(awk '{print int($1)}' /proc/uptime)"
            start_s=$(( start_ticks / hz ))
            LAST_UPTIME_MIN=$(( (uptime_s - start_s) / 60 ))
        else
            LAST_PID="DEAD"
            LAST_UPTIME_MIN=0
        fi
    else
        LAST_PID="--"
        LAST_UPTIME_MIN=0
    fi
}

collect_tick_data() {
    if [[ ! -f "${SERVER_LOG}" ]]; then return; fi

    # Extract the last 120 tick log lines
    local tick_lines
    tick_lines="$(grep 'total=.*ms' "${SERVER_LOG}" 2>/dev/null | tail -120)"

    if [[ -z "${tick_lines}" ]]; then return; fi

    # Parse last MSPT
    LAST_MSPT="$(echo "${tick_lines}" | tail -1 | grep -oP 'total=\K[0-9]+\.[0-9]+'  || echo "0")"

    # Parse per-phase from last tick
    local last_line
    last_line="$(echo "${tick_lines}" | tail -1)"
    PHASE_PRE="$(echo "${last_line}"    | grep -oP 'PRE=\K[0-9]+\.[0-9]+'    || echo "0")"
    PHASE_WORLD="$(echo "${last_line}"  | grep -oP 'WORLD=\K[0-9]+\.[0-9]+'  || echo "0")"
    PHASE_ENTITY="$(echo "${last_line}" | grep -oP 'ENTITY=\K[0-9]+\.[0-9]+' || echo "0")"
    PHASE_CHUNK="$(echo "${last_line}"  | grep -oP 'CHUNK=\K[0-9]+\.[0-9]+'  || echo "0")"
    PHASE_POST="$(echo "${last_line}"   | grep -oP 'POST=\K[0-9]+\.[0-9]+'   || echo "0")"

    # Estimate TPS from timestamp frequency of last 40 tick lines
    LAST_TPS="$(echo "${tick_lines}" | tail -40 | awk '
        /\[([0-9]{2}:[0-9]{2}:[0-9]{2})\]/ {
            match($0, /\[([0-9]{2}:[0-9]{2}:[0-9]{2})\]/, a)
            split(a[1], t, ":")
            times[NR] = t[1]*3600 + t[2]*60 + t[3]
            count++
        }
        END {
            if (count < 2) { printf "20.00"; exit }
            elapsed = times[count] - times[1]
            if (elapsed <= 0) elapsed = 1
            printf "%.2f", (count-1) / elapsed
        }
    ')"

    # Append to rolling history
    MSPT_HISTORY+=( "${LAST_MSPT}" )
    TPS_HISTORY+=( "${LAST_TPS}" )
    if (( ${#MSPT_HISTORY[@]} > MAX_HISTORY )); then
        MSPT_HISTORY=( "${MSPT_HISTORY[@]:1}" )
        TPS_HISTORY=( "${TPS_HISTORY[@]:1}" )
    fi

    # Recent spikes
    RECENT_SPIKES=()
    while IFS= read -r line; do
        RECENT_SPIKES+=("${line}")
    done < <(echo "${tick_lines}" | grep '\[SPIKE\]\|\[OVERRUN\]' | tail -5 || true)
}

collect_system_metrics() {
    # Temperature
    if command -v vcgencmd &>/dev/null; then
        LAST_TEMP="$(vcgencmd measure_temp 2>/dev/null | grep -oP '[0-9]+\.[0-9]+')°C"
    elif [[ -f /sys/class/thermal/thermal_zone0/temp ]]; then
        local raw_mk
        raw_mk="$(cat /sys/class/thermal/thermal_zone0/temp)"
        LAST_TEMP="$(echo "${raw_mk}" | awk '{printf "%.1f", $1/1000}')°C"
    fi
}

collect_health() {
    if [[ "${OPT_NO_HEALTH}" == true ]]; then
        HEALTH_SUMMARY="disabled"
        return
    fi
    if [[ -x "${HEALTH_CHECK}" ]]; then
        HEALTH_SUMMARY="$("${HEALTH_CHECK}" --brief 2>/dev/null || echo "ERROR")"
    else
        HEALTH_SUMMARY="health-check.sh not found"
    fi
}

collect_aggregate() {
    if [[ -z "${OPT_AGGREGATE_FILE}" || ! -f "${OPT_AGGREGATE_FILE}" ]]; then
        AGGREGATE_SUMMARY="disabled"
        return
    fi

    if ! command -v jq &>/dev/null; then
        AGGREGATE_SUMMARY="jq required"
        return
    fi

    AGGREGATE_SUMMARY="$(jq -r '
        "nodes=" + ((.nodes | length) | tostring)
        + " alive=" + ((.totals.aliveNodes // 0) | tostring)
        + " players=" + ((.totals.totalPlayers // 0) | tostring)
        + " avgTPS=" + ((.totals.avgTps // 0) | tostring)
        + " worstMSPT=" + ((.totals.worstMspt // 0) | tostring)
    ' "${OPT_AGGREGATE_FILE}" 2>/dev/null || echo "invalid aggregate data")"
}

# ── Rendering ─────────────────────────────────────────────────────────────────

TERM_COLS=80
TERM_ROWS=30

update_term_size() {
    TERM_COLS="$(tput cols  2>/dev/null || echo 80)"
    TERM_ROWS="$(tput lines 2>/dev/null || echo 30)"
}

render_dashboard() {
    update_term_size

    # Move to top-left without clearing (reduces flicker)
    tput cup 0 0

    local width="${TERM_COLS}"
    local sep
    sep="$(printf '─%.0s' $(seq 1 "${width}"))"

    # ── Header ────────────────────────────────────────────────────────────
    local pid_display="${LAST_PID}"
    local uptime_display="${LAST_UPTIME_MIN}m"
    [[ "${LAST_PID}" == "DEAD" || "${LAST_PID}" == "--" ]] && pid_display="${R}${LAST_PID}${RST}"

    printf "${BOLD}${C}  Cinder Control${RST}  "
    printf "preset=${W}${LAST_PRESET}${RST}  "
    printf "heap=${DIM}${LAST_HEAP_MIN}–${LAST_HEAP_MAX}${RST}  "
    printf "pid=${W}%s${RST}  " "${pid_display}"
    printf "up=${W}%s${RST}  " "${uptime_display}"
    printf "temp=${W}%s${RST}" "${LAST_TEMP}"
    printf "\n"
    echo -e "${DIM}${sep}${RST}"

    # ── TPS gauge ─────────────────────────────────────────────────────────
    local tps_col
    tps_col="$(tps_colour "${LAST_TPS}")"
    local tps_bar
    tps_bar="$(progress_bar "${LAST_TPS}" "20" "30" "█" "░")"

    printf "  ${BOLD}TPS${RST}   "
    printf "${tps_col}${BOLD}%6s${RST} " "${LAST_TPS}"
    printf "${tps_col}%s${RST}" "${tps_bar}"
    printf "  /20\n"

    # ── MSPT gauge ────────────────────────────────────────────────────────
    local mspt_col
    mspt_col="$(mspt_colour "${LAST_MSPT}")"
    local mspt_bar
    mspt_bar="$(progress_bar "${LAST_MSPT}" "50" "30" "█" "░")"

    printf "  ${BOLD}MSPT${RST}  "
    printf "${mspt_col}${BOLD}%6s${RST} " "${LAST_MSPT}ms"
    printf "${mspt_col}%s${RST}" "${mspt_bar}"
    printf "  /50ms\n"

    echo -e "${DIM}${sep}${RST}"

    # ── MSPT Sparkline ────────────────────────────────────────────────────
    local spark_width=$(( width - 14 ))
    local spark_values="${MSPT_HISTORY[*]:-0}"
    local sparkline
    sparkline="$(build_sparkline "${spark_values}" "${spark_width}")"
    printf "  ${BOLD}MSPT/60s${RST}  ${C}%s${RST}\n" "${sparkline}"

    echo -e "${DIM}${sep}${RST}"

    # ── Phase breakdown ───────────────────────────────────────────────────
    printf "  ${BOLD}Tick phases${RST}  "
    printf "${DIM}PRE${RST} %s  " "${PHASE_PRE}ms"
    printf "${DIM}WORLD${RST} %s  " "${PHASE_WORLD}ms"
    printf "${Y}ENTITY${RST} %s  " "${PHASE_ENTITY}ms"
    printf "${B}CHUNK${RST} %s  " "${PHASE_CHUNK}ms"
    printf "${DIM}POST${RST} %s" "${PHASE_POST}ms"
    printf "\n"

    echo -e "${DIM}${sep}${RST}"

    # ── Health summary ────────────────────────────────────────────────────
    local health_col="${G}"
    echo "${HEALTH_SUMMARY}" | grep -qi "FAIL" && health_col="${R}"
    echo "${HEALTH_SUMMARY}" | grep -qi "WARN" && health_col="${Y}"

    printf "  ${BOLD}Health${RST}  ${health_col}%s${RST}\n" "${HEALTH_SUMMARY}"

    if [[ "${AGGREGATE_SUMMARY}" != "disabled" ]]; then
        printf "  ${BOLD}Cluster${RST}  ${C}%s${RST}\n" "${AGGREGATE_SUMMARY}"
    fi

    echo -e "${DIM}${sep}${RST}"

    # ── Recent spikes ─────────────────────────────────────────────────────
    if (( ${#RECENT_SPIKES[@]} > 0 )); then
        printf "  ${BOLD}${R}Recent lag events${RST}\n"
        for spike in "${RECENT_SPIKES[@]}"; do
            local tick_num mspt_val
            tick_num="$(echo "${spike}" | grep -oP 'tick=\K[0-9]+'  || echo '?')"
            mspt_val="$(echo "${spike}" | grep -oP 'total=\K[0-9.]+' || echo '?')"
            local event_type="OVERRUN"
            echo "${spike}" | grep -q '\[SPIKE\]' && event_type="SPIKE"
            printf "    ${Y}tick %-10s  %sms  [%s]${RST}\n" \
                "${tick_num}" "${mspt_val}" "${event_type}"
        done
    else
        printf "  ${G}No lag events in recent log${RST}\n"
    fi

    echo -e "${DIM}${sep}${RST}"

    # ── Footer ────────────────────────────────────────────────────────────
    printf "  ${DIM}Refresh: ${OPT_INTERVAL}s  |  Ctrl+C to exit  |  %s${RST}\n" \
        "$(date -u '+%H:%M:%S UTC')"

    # Clear to end of screen (removes any leftover lines from previous render)
    tput ed 2>/dev/null || true
}

# ── Cleanup on exit ───────────────────────────────────────────────────────────

cleanup() {
    tput cnorm 2>/dev/null || true   # Restore cursor
    tput rmcup 2>/dev/null || true   # Restore terminal buffer
    echo ""
    echo "Cinder Control — dashboard exited."
}
trap cleanup EXIT INT TERM

# ── Entry point ───────────────────────────────────────────────────────────────

# Switch to alternate screen buffer and hide cursor
tput smcup 2>/dev/null || true
tput civis 2>/dev/null || true
clear

# Initial data collection
collect_launch_meta
collect_pid_info

# Main loop
while true; do
    collect_tick_data
    collect_system_metrics
    collect_pid_info
    collect_aggregate

    # Run health check every 10 refresh cycles (~20s default) to reduce overhead
    static_health_counter="${static_health_counter:-0}"
    if (( static_health_counter == 0 )); then
        collect_health
    fi
    static_health_counter=$(( (static_health_counter + 1) % 10 ))

    render_dashboard

    sleep "${OPT_INTERVAL}"
done
