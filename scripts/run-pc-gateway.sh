#!/usr/bin/env bash
# One-action PC Gateway launcher (macOS / Linux). Mirrors scripts/run-pc-gateway.ps1.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export RELAY_PROFILE="${RELAY_PROFILE:-production}"
export RELAY_GATEWAY_LAN_MODE="${RELAY_GATEWAY_LAN_MODE:-disabled}"
if [[ -z "${RELAY_GATEWAY_HOST:-}" ]]; then
  if [[ "$RELAY_PROFILE" == "development" ]]; then export RELAY_GATEWAY_HOST="0.0.0.0"; else export RELAY_GATEWAY_HOST="127.0.0.1"; fi
fi
export RELAY_GATEWAY_PORT="${RELAY_GATEWAY_PORT:-8080}"
export RELAY_GATEWAY_DB="${RELAY_GATEWAY_DB:-$HOME/.relay/relay-gateway.db}"
export RELAY_GATEWAY_ID="${RELAY_GATEWAY_ID:-pc-gateway-local}"
export RELAY_GATEWAY_ANONYMOUS_INGRESS="${RELAY_GATEWAY_ANONYMOUS_INGRESS:-$([[ "$RELAY_PROFILE" == "development" ]] && echo true || echo false)}"
export RELAY_GATEWAY_LAN_DISCOVERY="${RELAY_GATEWAY_LAN_DISCOVERY:-$([[ "$RELAY_PROFILE" == "development" ]] && echo true || echo false)}"

NO_BROWSER=0
SKIP_BUILD=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-browser) NO_BROWSER=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --port) export RELAY_GATEWAY_PORT="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$(dirname "$RELAY_GATEWAY_DB")"

BIN="$ROOT/pc-gateway/build/install/pc-gateway/bin/pc-gateway"
if [[ ! -x "$BIN" ]]; then
  if [[ "$SKIP_BUILD" -eq 1 ]]; then
    echo "Missing installDist: $BIN" >&2
    exit 1
  fi
  echo "First run: building PC Gateway (installDist)..."
  ./gradlew :pc-gateway:installDist
fi

CONSOLE="http://127.0.0.1:${RELAY_GATEWAY_PORT}/"
HEALTH="http://127.0.0.1:${RELAY_GATEWAY_PORT}/api/health"

echo ""
echo "========================================"
echo "  Relay PC Gateway"
echo "========================================"
echo "  Console : $CONSOLE"
echo "  Health  : $HEALTH"
echo "  DB      : $RELAY_GATEWAY_DB"
echo "  Profile : $RELAY_PROFILE / LAN: $RELAY_GATEWAY_LAN_MODE"
echo "  Host    : ${RELAY_GATEWAY_HOST}:${RELAY_GATEWAY_PORT}"
echo "========================================"
echo "  Stop with Ctrl+C in this window."
echo "  First run: create a named administrator with bootstrap-admin; no default password exists."
echo ""

if [[ "$NO_BROWSER" -eq 0 ]]; then
  (
    for _ in $(seq 1 60); do
      if curl -fsS "$HEALTH" >/dev/null 2>&1; then
        if command -v open >/dev/null 2>&1; then open "$CONSOLE" || true
        elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$CONSOLE" || true
        fi
        break
      fi
      sleep 0.5
    done
  ) &
fi

exec "$BIN"
