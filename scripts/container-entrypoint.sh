#!/usr/bin/env bash
# Entry point for the emulator container (docker/emulator.Dockerfile).
#
# The emulator binds its own ports (5554 console / 5555 adb) to 127.0.0.1 *inside*
# the container, so Docker's port publishing cannot reach them: the proxy connects
# from the bridge, not from the container's loopback, and every `adb connect` from
# the host fails.
#
# The supported way around that is to leave adb inside the container and have the
# host talk to that server over TCP:
#
#   * start the adb server here with -a so it listens on all interfaces
#     (published as 15037 on the host), before the emulator boots, so the emulator
#     registers `emulator-5554` with it;
#   * on the host, env.sh exports ADB_SERVER_SOCKET=tcp:127.0.0.1:15037, which both
#     the adb CLI and AGP honour.
#
# Usage: container-entrypoint.sh <emulator> [emulator args...]
set -euo pipefail

ADB="$1"
shift

# `-a`: bind 0.0.0.0 so the published port reaches us. `-P`: keep the standard 5037
# inside the container. Failures are non-fatal: the emulator starts its own server if
# it has to, and we would then simply lose host-side adb access.
"$ADB" -a -P 5037 start-server >/dev/null 2>&1 || true

exec "$@"
