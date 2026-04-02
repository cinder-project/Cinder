#!/usr/bin/env bash
# =============================================================================
# Cinder Runtime - launch.sh
# PaperMC server launcher for Raspberry Pi focused Cinder OS images.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

readonly CINDER_VERSION="1.0.0"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"
readonly LAUNCH_TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

: "${CINDER_BASE_DIR:="${SCRIPT_DIR}/../.."}"
: "${CINDER_JAR:="${CINDER_BASE_DIR}/server/paper-server.jar"}"
: "${CINDER_LOG_DIR:="${CINDER_BASE_DIR}/logs"}"
: "${CINDER_PRESET_DIR:="${SCRIPT_DIR}/../presets"}"
: "${CINDER_LAUNCH_CWD:="${CINDER_BASE_DIR}"}"
: "${CINDER_EULA_FILE:="${CINDER_BASE_DIR}/eula.txt"}"

: "${CINDER_HEAP_MIN:="512m"}"
: "${CINDER_HEAP_MAX:="2g"}"
: "${CINDER_PRESET:="survival"}"
: "${CINDER_AUTO_ACCEPT_EULA:="true"}"
: "${PAPER_SERVER_ARGS:="nogui"}"

readonly WATCHDOG_MAX_RESTARTS=5
readonly WATCHDOG_RESTART_DELAY_S=10
readonly WATCHDOG_CRASH_WINDOW_S=300

OPT_PRESET="${CINDER_PRESET}"
OPT_DRY_RUN=false
OPT_NO_WATCHDOG=false
OPT_DEBUG=false
OPT_ACCEPT_EULA=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --preset)
            OPT_PRESET="${2:?--preset requires a value}"; shift 2 ;;
        --dry-run)
            OPT_DRY_RUN=true; shift ;;
        --no-watchdog)
            OPT_NO_WATCHDOG=true; shift ;;
        --debug)
            OPT_DEBUG=true; shift ;;
        --accept-eula)
            OPT_ACCEPT_EULA=true; shift ;;
        --paper-args)
            PAPER_SERVER_ARGS="${2:?--paper-args requires a value}"; shift 2 ;;
        --heap-max)
            CINDER_HEAP_MAX="${2:?--heap-max requires a value}"; shift 2 ;;
        --heap-min)
            CINDER_HEAP_MIN="${2:?--heap-min requires a value}"; shift 2 ;;
        -h|--help)
            cat <<'EOF'
Usage:
  launch.sh [options]

Options:
  --preset <name>        Runtime preset name (default: survival)
  --dry-run              Print resolved configuration and exit
  --no-watchdog          Disable restart loop
  --debug                Enable JVM debug port 5005
  --accept-eula          Write eula=true before launch
    --paper-args <args>    Extra PaperMC args string (default: nogui)
  --heap-min <size>      Minimum JVM heap (e.g. 512m)
  --heap-max <size>      Maximum JVM heap (e.g. 2g)
EOF
            exit 0 ;;
        *)
            echo "[launch] Unknown argument: $1" >&2
            exit 1 ;;
    esac
done

mkdir -p "${CINDER_LOG_DIR}"
LAUNCH_LOG="${CINDER_LOG_DIR}/launch-${LAUNCH_TIMESTAMP}.log"

log()  { local m="[$(date -u +%H:%M:%S)] [launch] $*"; echo "${m}"; echo "${m}" >> "${LAUNCH_LOG}"; }
warn() { local m="[$(date -u +%H:%M:%S)] [launch:WARN] $*"; echo "${m}" >&2; echo "${m}" >> "${LAUNCH_LOG}"; }
die()  { local m="[$(date -u +%H:%M:%S)] [launch:FATAL] $*"; echo "${m}" >&2; echo "${m}" >> "${LAUNCH_LOG}"; exit 1; }

log "Cinder Runtime ${CINDER_VERSION} - PaperMC launch starting"
log "Preset: ${OPT_PRESET} | dry-run: ${OPT_DRY_RUN} | watchdog: $( [[ "${OPT_NO_WATCHDOG}" == true ]] && echo disabled || echo enabled )"

PRESET_FILE="${CINDER_PRESET_DIR}/${OPT_PRESET}.conf"
if [[ ! -f "${PRESET_FILE}" ]]; then
    die "Preset file not found: ${PRESET_FILE}"
fi

# shellcheck source=/dev/null
source "${PRESET_FILE}"

: "${CINDER_HEAP_MIN:="${PRESET_HEAP_MIN:-512m}"}"
: "${CINDER_HEAP_MAX:="${PRESET_HEAP_MAX:-2g}"}"

log "Validating runtime environment..."

if ! command -v java >/dev/null 2>&1; then
    die "java not found in PATH. Install OpenJDK 21."
fi

JAVA_VERSION_OUTPUT="$(java -version 2>&1 | head -1)"
JAVA_MAJOR="$(java -version 2>&1 | grep -oP '(?<=version ")\d+' | head -1)"

if [[ -z "${JAVA_MAJOR}" || "${JAVA_MAJOR}" -lt 17 ]]; then
    die "Java 17+ required. Found: ${JAVA_VERSION_OUTPUT}"
fi

log "Java: ${JAVA_VERSION_OUTPUT} (major=${JAVA_MAJOR})"

if [[ ! -f "${CINDER_JAR}" ]]; then
    die "PaperMC server jar not found: ${CINDER_JAR}"
fi

if [[ ! -d "${CINDER_LAUNCH_CWD}" ]]; then
    mkdir -p "${CINDER_LAUNCH_CWD}"
fi

if [[ "${OPT_ACCEPT_EULA}" == true || "${CINDER_AUTO_ACCEPT_EULA}" == true ]]; then
    echo "eula=true" > "${CINDER_EULA_FILE}"
    log "EULA accepted at ${CINDER_EULA_FILE}"
elif [[ -f "${CINDER_EULA_FILE}" ]] && grep -q '^eula=true$' "${CINDER_EULA_FILE}"; then
    log "EULA already accepted"
else
    warn "EULA is not accepted. Server may exit until eula=true is present at ${CINDER_EULA_FILE}."
fi

TOTAL_KB="$(grep MemTotal /proc/meminfo | awk '{print $2}')"
TOTAL_MB=$(( TOTAL_KB / 1024 ))
if [[ "${TOTAL_MB}" -lt 4096 ]]; then
    warn "System RAM is ${TOTAL_MB} MB - recommend 8GB Pi 4 for stable hosting"
fi
log "System RAM: ${TOTAL_MB} MB"

NPROC="$(nproc)"
if [[ "${NPROC}" -ge 2 ]]; then
    CPU_AFFINITY="1-$(( NPROC - 1 ))"
else
    CPU_AFFINITY="0"
    warn "Single-core system detected - skipping affinity isolation"
fi
log "JVM CPU affinity: ${CPU_AFFINITY}"

JVM_MEMORY_FLAGS=(
    "-Xms${CINDER_HEAP_MIN}"
    "-Xmx${CINDER_HEAP_MAX}"
)

JVM_GC_FLAGS=(
    "-XX:+UseG1GC"
    "-XX:MaxGCPauseMillis=25"
    "-XX:G1HeapRegionSize=8m"
    "-XX:G1NewSizePercent=20"
    "-XX:G1MaxNewSizePercent=40"
    "-XX:InitiatingHeapOccupancyPercent=15"
    "-XX:+PerfDisableSharedMem"
    "-XX:-OmitStackTraceInFastThrow"
)

if [[ -n "${PRESET_GC_FLAGS:-}" ]]; then
    # shellcheck disable=SC2206
    JVM_GC_FLAGS=(${PRESET_GC_FLAGS})
    log "GC flags overridden by preset"
fi

JVM_RUNTIME_FLAGS=(
    "-server"
    "-XX:+AlwaysPreTouch"
    "-XX:+DisableExplicitGC"
    "-XX:+UseStringDeduplication"
    "-Djava.awt.headless=true"
    "-Dfile.encoding=UTF-8"
    "-Dcinder.preset=${OPT_PRESET}"
    "-Dcinder.version=${CINDER_VERSION}"
)

JVM_DEBUG_FLAGS=()
if [[ "${OPT_DEBUG}" == true ]]; then
    JVM_DEBUG_FLAGS=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
    warn "Debug mode enabled - JVM debug port 5005 open"
fi

PRESET_EXTRA_ARR=()
if [[ -n "${PRESET_EXTRA_FLAGS:-}" ]]; then
    # shellcheck disable=SC2206
    PRESET_EXTRA_ARR=(${PRESET_EXTRA_FLAGS})
fi

PAPER_ARGS_ARR=()
if [[ -n "${PAPER_SERVER_ARGS}" ]]; then
    # shellcheck disable=SC2206
    PAPER_ARGS_ARR=(${PAPER_SERVER_ARGS})
fi

JVM_ARGS=(
    "${JVM_MEMORY_FLAGS[@]}"
    "${JVM_GC_FLAGS[@]}"
    "${JVM_RUNTIME_FLAGS[@]}"
    "${JVM_DEBUG_FLAGS[@]}"
    "${PRESET_EXTRA_ARR[@]}"
)

SERVER_LOG="${CINDER_LOG_DIR}/paper-server.log"
if [[ -s "${SERVER_LOG}" ]]; then
    ROTATED="${CINDER_LOG_DIR}/paper-server-$(date -u +%Y%m%dT%H%M%SZ).log.gz"
    gzip -c "${SERVER_LOG}" > "${ROTATED}" && : > "${SERVER_LOG}"
    log "Rotated previous server log -> ${ROTATED}"
fi

find "${CINDER_LOG_DIR}" -name "paper-server-*.log.gz" -mtime +14 -delete 2>/dev/null || true

if [[ "${OPT_DRY_RUN}" == true ]]; then
    log "=== DRY RUN - resolved launch configuration ==="
    log "Jar:          ${CINDER_JAR}"
    log "Launch CWD:   ${CINDER_LAUNCH_CWD}"
    log "Logs:         ${CINDER_LOG_DIR}"
    log "EULA file:    ${CINDER_EULA_FILE}"
    log "Preset:       ${OPT_PRESET} (${PRESET_FILE})"
    log "Heap:         ${CINDER_HEAP_MIN} - ${CINDER_HEAP_MAX}"
    log "CPU affinity: ${CPU_AFFINITY}"
    log "Paper args:   ${PAPER_SERVER_ARGS}"
    log "JVM args:"
    for flag in "${JVM_ARGS[@]}"; do
        log "  ${flag}"
    done
    log "=== Dry run complete. Exiting. ==="
    exit 0
fi

SERVER_PID=""
WATCHDOG_STOP=false

handle_stop_signal() {
    log "Received stop signal - requesting clean server shutdown"
    WATCHDOG_STOP=true
    if [[ -n "${SERVER_PID}" ]]; then
        kill -TERM "${SERVER_PID}" 2>/dev/null || true
    fi
}

trap 'handle_stop_signal' TERM
trap 'handle_stop_signal' INT

launch_server() {
    log "----------------------------------------------"
    log "Launching PaperMC server"
    log "  Jar:       ${CINDER_JAR}"
    log "  Preset:    ${OPT_PRESET}"
    log "  Heap:      ${CINDER_HEAP_MIN} - ${CINDER_HEAP_MAX}"
    log "  Affinity:  ${CPU_AFFINITY}"
    log "  CWD:       ${CINDER_LAUNCH_CWD}"
    log "  Arguments: ${PAPER_SERVER_ARGS}"
    log "----------------------------------------------"

    cat > "${CINDER_LOG_DIR}/last-launch.json" <<EOF
{
    "runtime": "papermc",
  "cinderVersion": "${CINDER_VERSION}",
  "preset": "${OPT_PRESET}",
  "heapMin": "${CINDER_HEAP_MIN}",
  "heapMax": "${CINDER_HEAP_MAX}",
  "cpuAffinity": "${CPU_AFFINITY}",
  "javaVersion": "${JAVA_VERSION_OUTPUT}",
  "launchTime": "${LAUNCH_TIMESTAMP}",
  "jar": "${CINDER_JAR}",
  "launchCwd": "${CINDER_LAUNCH_CWD}",
    "paperArgs": "${PAPER_SERVER_ARGS}"
}
EOF

    CMD=(
        taskset --cpu-list "${CPU_AFFINITY}"
        nice -n -5
        ionice -c 1 -n 2
        java "${JVM_ARGS[@]}"
        -jar "${CINDER_JAR}"
        "${PAPER_ARGS_ARR[@]}"
    )

    (
        cd "${CINDER_LAUNCH_CWD}"
        "${CMD[@]}"
    ) 2>&1 | tee -a "${SERVER_LOG}" &
    SERVER_PID=$!

    log "Server process PID: ${SERVER_PID}"
    echo "${SERVER_PID}" > "${CINDER_LOG_DIR}/cinder.pid"

    set +e
    wait "${SERVER_PID}"
    EXIT_CODE=$?
    set -e

    rm -f "${CINDER_LOG_DIR}/cinder.pid"
    log "Server process exited with code ${EXIT_CODE}"
    return "${EXIT_CODE}"
}

if [[ "${OPT_NO_WATCHDOG}" == true ]]; then
    set +e
    launch_server
    EXIT_CODE=$?
    set -e
    log "No-watchdog mode. Exiting with code ${EXIT_CODE}."
    exit "${EXIT_CODE}"
fi

RESTART_COUNT=0
WINDOW_START="$(date +%s)"

while true; do
    if [[ "${WATCHDOG_STOP}" == true ]]; then
        log "Watchdog stopping cleanly (operator signal)."
        exit 3
    fi

    set +e
    launch_server
    EXIT_CODE=$?
    set -e

    if [[ "${EXIT_CODE}" -eq 0 ]]; then
        log "Server shut down cleanly. Watchdog exiting."
        exit 0
    fi

    if [[ "${WATCHDOG_STOP}" == true ]]; then
        log "Watchdog stopping after server exit (operator signal)."
        exit 3
    fi

    NOW="$(date +%s)"
    WINDOW_ELAPSED=$(( NOW - WINDOW_START ))

    if [[ "${WINDOW_ELAPSED}" -gt "${WATCHDOG_CRASH_WINDOW_S}" ]]; then
        log "Watchdog: server was stable for ${WINDOW_ELAPSED}s - resetting restart counter."
        RESTART_COUNT=0
        WINDOW_START="${NOW}"
    fi

    RESTART_COUNT=$(( RESTART_COUNT + 1 ))
    if [[ "${RESTART_COUNT}" -gt "${WATCHDOG_MAX_RESTARTS}" ]]; then
        warn "Watchdog: server crashed ${RESTART_COUNT} times within ${WATCHDOG_CRASH_WINDOW_S}s."
        warn "Giving up. Check logs: ${SERVER_LOG}"
        warn "To restart manually: ${SCRIPT_DIR}/${SCRIPT_NAME} --preset ${OPT_PRESET}"
        exit 2
    fi

    warn "Watchdog: server crashed (exit=${EXIT_CODE}). Restart ${RESTART_COUNT}/${WATCHDOG_MAX_RESTARTS} in ${WATCHDOG_RESTART_DELAY_S}s..."
    sleep "${WATCHDOG_RESTART_DELAY_S}"
done