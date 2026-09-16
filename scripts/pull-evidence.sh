#!/usr/bin/env bash
# Pull the PNG evidence that BrowserAcceptanceTest writes inside the app.
#
#   KEEP_INSTALLED=1 scripts/connected-test.sh dev.sshrelay.BrowserAcceptanceTest
#   scripts/pull-evidence.sh
#
# The app has to still be installed: AGP removes it when a connected test run ends, and
# the screenshots live in the app's own storage. `run-as` reads them because the debug
# build is debuggable.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

CONTAINER_NAME="${CONTAINER_NAME:-sshrelay-emulator}"
PACKAGE="${PACKAGE:-dev.sshrelay}"
OUT="$ROOT/artifacts/device"
mkdir -p "$OUT"

# adb only sees the emulator from inside the container it runs in.
run_adb() {
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTAINER_NAME"; then
    docker exec -e "HOME=$SSHRELAY_TOOLCHAIN/adb-home" -u "$(id -u):$(id -g)" \
      "$CONTAINER_NAME" "$ANDROID_HOME/platform-tools/adb" "$@"
  else
    adb "$@"
  fi
}

if ! run_adb shell "run-as $PACKAGE ls files" >/dev/null 2>&1; then
  echo "cannot read $PACKAGE's files - is the app still installed (KEEP_INSTALLED=1)?" >&2
  exit 1
fi

# The tests prefer the app's *external* files dir and fall back to the internal one,
# so look in both.
EXTERNAL="/sdcard/Android/data/$PACKAGE/files"
COLLECTED=0
for name in $(run_adb shell "ls $EXTERNAL" 2>/dev/null | tr -d '\r' | grep -E '\.png$' || true); do
  run_adb exec-out "cat $EXTERNAL/$name" >"$OUT/$name"
  printf '   %-34s %s bytes\n' "$name" "$(stat -c%s "$OUT/$name")"
  COLLECTED=1
done
for name in $(run_adb shell "run-as $PACKAGE ls files" | tr -d '\r' | grep -E '\.png$' || true); do
  # Binary-safe copy: cat the file through the adb transport.
  run_adb exec-out "run-as $PACKAGE cat files/$name" >"$OUT/$name"
  printf '   %-34s %s bytes\n' "$name" "$(stat -c%s "$OUT/$name")"
  COLLECTED=1
done

if [ "$COLLECTED" -eq 0 ]; then
  echo "no PNG evidence found (looked in $EXTERNAL and $PACKAGE/files)" >&2
  exit 1
fi

echo
echo "evidence written to $OUT"
