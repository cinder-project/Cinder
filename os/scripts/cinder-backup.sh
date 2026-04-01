#!/usr/bin/env bash
# Wrapper entrypoint for roadmap naming compatibility.

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/backup.sh" "$@"
