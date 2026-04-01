#!/usr/bin/env bash
# Backward-compatible wrapper around cinder-report.sh for older tooling.

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/cinder-report.sh" "$@"
