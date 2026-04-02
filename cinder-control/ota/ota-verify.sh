#!/usr/bin/env bash
# =============================================================================
# Cinder Control - ota-verify.sh
# Validate OTA payload integrity using SHA-256.
# =============================================================================

set -euo pipefail
IFS=$'\n\t'

JAR_PATH=""
SHA256_FILE=""
EXPECTED_SHA256=""

_log()  { echo "[ota-verify] $(date -u +%H:%M:%SZ)  $*"; }
_pass() { _log "OK      $*"; }
_warn() { _log "WARN    $*"; }
_fail() { _log "FAIL    $*" >&2; exit 1; }

_usage() {
    cat <<'EOF'
Usage:
  ota-verify.sh --jar <path> [--sha256-file <path> | --expected-sha256 <hex>]
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar)
            JAR_PATH="${2:?--jar requires a value}"
            shift 2
            ;;
        --sha256-file)
            SHA256_FILE="${2:?--sha256-file requires a value}"
            shift 2
            ;;
        --expected-sha256)
            EXPECTED_SHA256="${2:?--expected-sha256 requires a value}"
            shift 2
            ;;
        -h|--help)
            _usage
            exit 0
            ;;
        *)
            _fail "Unknown argument: $1"
            ;;
    esac
done

[[ -n "${JAR_PATH}" ]] || _fail "--jar is required"
[[ -f "${JAR_PATH}" ]] || _fail "JAR not found: ${JAR_PATH}"

command -v sha256sum >/dev/null 2>&1 || _fail "sha256sum is required"

if [[ -n "${SHA256_FILE}" ]]; then
    [[ -f "${SHA256_FILE}" ]] || _fail "SHA256 file not found: ${SHA256_FILE}"
    EXPECTED_SHA256="$(awk 'NR==1{print $1}' "${SHA256_FILE}")"
fi

if [[ -z "${EXPECTED_SHA256}" ]]; then
    _fail "Either --sha256-file or --expected-sha256 must be provided"
fi

[[ "${EXPECTED_SHA256}" =~ ^[A-Fa-f0-9]{64}$ ]] || _fail "Expected SHA256 must be a 64-char hex string"

ACTUAL_SHA256="$(sha256sum "${JAR_PATH}" | awk '{print $1}')"

if [[ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]]; then
    _fail "Checksum mismatch for ${JAR_PATH}: expected=${EXPECTED_SHA256} actual=${ACTUAL_SHA256}"
fi

_pass "Checksum verified: ${ACTUAL_SHA256}"
