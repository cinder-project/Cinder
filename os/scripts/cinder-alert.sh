#!/usr/bin/env bash
# Wrapper for roadmap naming compatibility.

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${SCRIPT_DIR}/../../cinder-control/monitoring/cinder-alert.sh"
exec "${TARGET}" "$@"
