#!/bin/sh

# SPDX-License-Identifier: Apache-2.0

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$SCRIPT_DIR/gradle/wrapper/bootstrap-gradle-wrapper.sh" "$@"
