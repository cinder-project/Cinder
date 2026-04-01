#!/usr/bin/env bash
# Backward-compatible wrapper for roadmap naming.

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/metrics-collector.sh" "$@"
