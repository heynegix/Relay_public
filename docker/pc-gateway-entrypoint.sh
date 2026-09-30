#!/bin/sh
set -eu

# Docker Desktop bind mounts preserve Windows ACLs rather than POSIX owner-only permissions.
# Stage the private rescue key in this container's ephemeral filesystem before Gateway validates it.
# Nothing is printed and /run is discarded when the container stops.
private_dir=/run/relay-private
mkdir -p "$private_dir"
cp "$RELAY_RESCUE_KEY_FILE" "$private_dir/rescue-keys.json"
chmod 600 "$private_dir/rescue-keys.json"
export RELAY_RESCUE_KEY_FILE="$private_dir/rescue-keys.json"

exec java -cp '/opt/relay/lib/*' com.example.relay.pcgateway.MainKt
