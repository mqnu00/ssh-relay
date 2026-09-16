#!/usr/bin/env bash
# Cross-app acceptance: the phone's *browser* opens the forwarded port.
#
#   scripts/emulator.sh start
#   scripts/run-test-sshd.sh container
#   scripts/verify-browser.sh
#
# The check is split across two places on purpose:
#
#   * a script cannot ask the app to raise a tunnel, so an instrumented test
#     (`holdsTheTunnelOpenForExternalChecks`) brings it up and holds it;
#   * handing the URL to the *system browser* happens here, because doing it from
#     inside an instrumentation proved unreliable (see the ignored test in
#     BrowserAcceptanceTest for the details).
#
# The verdict does not depend on reading the browser's screen. The test HTTP server
# logs every page request together with its User-Agent, so a request carrying a Chrome
# User-Agent for our cache-busting URL is unambiguous proof that a *different app*
# fetched the forwarded page through the SSH tunnel.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

CONTAINER_NAME="${CONTAINER_NAME:-sshrelay-emulator}"
PORT="${PORT:-18080}"
PACKAGE="${PACKAGE:-dev.sshrelay}"
HOLD_SECONDS="${HOLD_SECONDS:-180}"
SERVER_LOG="$SSHRELAY_TOOLCHAIN/test-sshd/container.log"
OUT="$ROOT/artifacts"
mkdir -p "$OUT"

step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m   PASS\033[0m %s\n' "$*"; }
bad()  { printf '\033[1;31m   FAIL\033[0m %s\n' "$*"; }

if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTAINER_NAME"; then
  bad "emulator container '$CONTAINER_NAME' is not running; run scripts/emulator.sh start"
  exit 1
fi

adb_in() {
  docker exec -e "HOME=$SSHRELAY_TOOLCHAIN/adb-home" -u "$(id -u):$(id -g)" \
    "$CONTAINER_NAME" "$ANDROID_HOME/platform-tools/adb" "$@"
}

step "1. device"
adb_in devices | sed 's/^/   /'
adb_in shell getprop ro.build.version.release | tr -d '\r' | sed 's/^/   android /'

step "2. bring the tunnel up and hold it (instrumented, ${HOLD_SECONDS}s)"
# scripts/run-instrumented.sh rather than connected-test.sh: the APKs are already
# built, and skipping Gradle keeps ~1.2GB of build JVM out of a host that is already
# tight (the emulator alone wants ~2.6GB).
nohup bash "$ROOT/scripts/run-instrumented.sh" \
  'dev.sshrelay.BrowserAcceptanceTest#holdsTheTunnelOpenForExternalChecks' \
  -e holdMs "$((HOLD_SECONDS * 1000))" \
  >"$SSHRELAY_TOOLCHAIN/hold-test.log" 2>&1 &
HOLD_PID=$!

# The test writes this file once its tunnel is up. Polling the device's
# /proc/net/tcp is not possible (Android restricts it for the shell user) and
# instrumentation stdout is only flushed when the test finishes.
READY_FILE="/sdcard/Android/data/$PACKAGE/files/tunnel-ready.txt"
adb_in shell "rm -f $READY_FILE" >/dev/null 2>&1 || true
printf '   waiting for the app to report its listener'
UP=0
for _ in $(seq 1 60); do
  if adb_in shell "[ -f $READY_FILE ] && echo yes" 2>/dev/null | grep -q yes; then
    UP=1
    break
  fi
  printf '.'
  sleep 5
done
printf '\n'
if [ "$UP" -eq 1 ]; then
  ok "the app is listening on 127.0.0.1:$PORT"
else
  bad "the tunnel never came up; hold-test log:"
  tail -20 "$SSHRELAY_TOOLCHAIN/hold-test.log" | sed 's/^/   /'
  exit 1
fi

step "3. hand the URL to the system browser (a different app, different uid)"
TAG="$(date +%s)"
URL="http://127.0.0.1:$PORT/?proof=$TAG"
echo "   $URL"
BROWSER="$(adb_in shell "pm resolve-activity -a android.intent.action.VIEW -d '$URL'" 2>/dev/null \
  | tr -d '\r' | awk -F= '/packageName=/{print $2; exit}')"
echo "   resolved browser app: ${BROWSER:-<none>}"
adb_in shell "am start -a android.intent.action.VIEW -d '$URL'" >/dev/null 2>&1 || true
sleep 12

step "4. did that app actually fetch the page through the tunnel?"
LINE="$(grep -a "proof=$TAG" "$SERVER_LOG" 2>/dev/null | head -1 || true)"
if [ -n "$LINE" ]; then
  ok "the remote service was reached for this exact URL:"
  echo "   $LINE"
  if grep -a "proof=$TAG" "$SERVER_LOG" | grep -q "Chrome/"; then
    ok "the request carried a Chrome User-Agent, so a real browser engine fetched it"
  else
    bad "the request did not carry a Chrome User-Agent"
  fi
  adb_in exec-out screencap -p >"$OUT/browser-system-app.png" 2>/dev/null || true
else
  bad "no request for proof=$TAG reached the remote service"
  echo "   last requests seen by the server:"
  grep -a "http request #" "$SERVER_LOG" | tail -5 | sed 's/^/   /'
fi

step "5. evidence collected in artifacts/"
for f in "$OUT"/browser-webview-forward.png "$OUT"/browser-system-app.png \
         "$OUT"/app-tunnel-running.png "$OUT"/openssh-forward.html; do
  [ -f "$f" ] && printf '   %-34s %s bytes\n' "$(basename "$f")" "$(stat -c%s "$f")"
done

echo
echo "waiting for the hold test to finish ..."
wait "$HOLD_PID" 2>/dev/null || true
echo "done"
