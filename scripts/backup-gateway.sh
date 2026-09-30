#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec pwsh -NoProfile -File "$ROOT/scripts/backup-gateway.ps1" "$@"
