#!/usr/bin/env bash
set -eo pipefail
export SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"
# shellcheck source=/dev/null
set +u
source "$SDKMAN_DIR/bin/sdkman-init.sh"
sdk use java 25.0.4-tem >/dev/null
sdk use maven 3.9.11 >/dev/null
