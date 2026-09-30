#!/usr/bin/env bash
# macOS setup for Relay PC Gateway. Production starts loopback-only; LAN exposure is explicit.
# Requires: Java 17+, admin for pf firewall rules and launchd (optional).
set -euo pipefail

PORT="${RELAY_GATEWAY_PORT:-8080}"
DISCOVERY_PORT="${RELAY_GATEWAY_DISCOVERY_PORT:-42888}"
PROFILE="${RELAY_PROFILE:-production}"
LAN_MODE="${RELAY_GATEWAY_LAN_MODE:-disabled}"
HOST="${RELAY_GATEWAY_HOST:-127.0.0.1}"
ANONYMOUS_INGRESS="${RELAY_GATEWAY_ANONYMOUS_INGRESS:-false}"
LAN_DISCOVERY="${RELAY_GATEWAY_LAN_DISCOVERY:-false}"
GATEWAY_HOME="${RELAY_GATEWAY_HOME:-$HOME/.relay}"
LABEL="com.example.relay.pcgateway"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
BIN="$ROOT/pc-gateway/build/install/pc-gateway/bin/pc-gateway"

usage() {
  cat <<EOF
Usage: $0 [--install-dist] [--firewall] [--autostart] [--all]

  --install-dist   Run Gradle :pc-gateway:installDist
  --firewall       Open requested closed-network TCP $PORT and optional UDP $DISCOVERY_PORT via pf (needs sudo)
  --autostart      Install LaunchAgent that starts Gateway at login
  --all            install-dist + firewall + autostart

Env: RELAY_PROFILE, RELAY_GATEWAY_LAN_MODE, RELAY_GATEWAY_HOST,
     RELAY_GATEWAY_ANONYMOUS_INGRESS, RELAY_GATEWAY_LAN_DISCOVERY,
     RELAY_GATEWAY_PORT, RELAY_GATEWAY_DISCOVERY_PORT, RELAY_GATEWAY_DB
EOF
}

do_install_dist=0
do_firewall=0
do_autostart=0

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi

for arg in "$@"; do
  case "$arg" in
    --install-dist) do_install_dist=1 ;;
    --firewall) do_firewall=1 ;;
    --autostart) do_autostart=1 ;;
    --all) do_install_dist=1; do_firewall=1; do_autostart=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown: $arg"; usage; exit 1 ;;
  esac
done

if [[ "$LAN_DISCOVERY" == "true" && "$ANONYMOUS_INGRESS" != "true" ]]; then
  echo "Safety stop: LAN discovery advertises anonymous sync; enable anonymous ingress too or leave discovery disabled." >&2
  exit 1
fi

mkdir -p "$GATEWAY_HOME"

if [[ "$do_install_dist" -eq 1 ]]; then
  echo "==> Gradle installDist"
  (cd "$ROOT" && ./gradlew :pc-gateway:installDist)
  test -x "$BIN" || { echo "missing $BIN"; exit 1; }
  echo "OK: $BIN"
fi

if [[ "$do_firewall" -eq 1 ]]; then
  if [[ "$LAN_MODE" != "closed-network" || "$HOST" == "127.0.0.1" || "$HOST" == "::1" ]]; then
    echo "Safety stop: pf rules require explicit RELAY_GATEWAY_LAN_MODE=closed-network and a non-loopback RELAY_GATEWAY_HOST." >&2
    exit 1
  fi
  echo "==> pf firewall anchors (requires sudo)"
  # User-level reminder: macOS Application Firewall may still prompt on first bind.
  ANCHOR="/etc/pf.anchors/com.example.relay.pcgateway"
  CONF_SNIPPET="
# Relay PC Gateway — Private LAN only is the operator's responsibility.
pass in quick proto tcp from any to any port ${PORT}
pass in quick proto udp from any to any port ${DISCOVERY_PORT}
"
  TMP="$(mktemp)"
  printf '%s\n' "$CONF_SNIPPET" >"$TMP"
  sudo cp "$TMP" "$ANCHOR"
  rm -f "$TMP"
  if ! grep -q 'com.example.relay.pcgateway' /etc/pf.conf 2>/dev/null; then
    echo "Add to /etc/pf.conf (once):"
    echo "  anchor \"com.example.relay.pcgateway\""
    echo "  load anchor \"com.example.relay.pcgateway\" from \"$ANCHOR\""
    echo "Then: sudo pfctl -f /etc/pf.conf && sudo pfctl -e"
  else
    sudo pfctl -f /etc/pf.conf || true
    echo "Reloaded pf.conf (if enabled)"
  fi
  echo "OK: firewall notes applied for TCP/${PORT} UDP/${DISCOVERY_PORT}"
fi

if [[ "$do_autostart" -eq 1 ]]; then
  echo "==> LaunchAgent $LABEL"
  if [[ ! -x "$BIN" ]]; then
    echo "Run with --install-dist first"
    exit 1
  fi
  mkdir -p "$(dirname "$PLIST")"
  cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${BIN}</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>RELAY_GATEWAY_HOST</key>
    <string>${HOST}</string>
    <key>RELAY_PROFILE</key>
    <string>${PROFILE}</string>
    <key>RELAY_GATEWAY_LAN_MODE</key>
    <string>${LAN_MODE}</string>
    <key>RELAY_GATEWAY_PORT</key>
    <string>${PORT}</string>
    <key>RELAY_GATEWAY_DB</key>
    <string>${GATEWAY_HOME}/relay-gateway.db</string>
    <key>RELAY_GATEWAY_LAN_DISCOVERY</key>
    <string>${LAN_DISCOVERY}</string>
    <key>RELAY_GATEWAY_ANONYMOUS_INGRESS</key>
    <string>${ANONYMOUS_INGRESS}</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>${GATEWAY_HOME}/gateway.stdout.log</string>
  <key>StandardErrorPath</key>
  <string>${GATEWAY_HOME}/gateway.stderr.log</string>
  <key>WorkingDirectory</key>
  <string>${GATEWAY_HOME}</string>
</dict>
</plist>
EOF
  launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
  launchctl bootstrap "gui/$(id -u)" "$PLIST"
  launchctl enable "gui/$(id -u)/${LABEL}"
  launchctl kickstart -k "gui/$(id -u)/${LABEL}"
  echo "OK: LaunchAgent installed → $PLIST"
  echo "Health: curl -sS http://127.0.0.1:${PORT}/api/health"
fi

echo "Done."
