#!/usr/bin/env bash
# Run the instrumented tests without Gradle: install the prebuilt APKs and drive
# `am instrument` directly.
#
#   scripts/run-instrumented.sh                                     # every class
#   scripts/run-instrumented.sh dev.sshrelay.TunnelEndToEndTest      # one class
#   scripts/run-instrumented.sh 'dev.sshrelay.BrowserAcceptanceTest#holdsTheTunnelOpenForExternalChecks' \
#       -e holdMs 180000
#
# Why this exists next to scripts/connected-test.sh:
#
#   The emulator wants ~2.6GB, and this host has 7.6GB shared with whatever else is
#   running (the user's gitlab container alone holds ~3.1GB). A Gradle build JVM adds
#   another ~1.2GB on top, which tipped the host into OOM and got the emulator killed
#   mid-suite. The APKs are already produced by
#
#       ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
#
#   so *running* them needs no Gradle at all — just adb. Use this when memory is tight;
#   use scripts/connected-test.sh when you also want Gradle's reporting/UTP behaviour.
#
# Unlike `connectedDebugAndroidTest`, this leaves the app installed afterwards, which
# is what scripts/pull-evidence.sh wants.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

CONTAINER_NAME="${CONTAINER_NAME:-sshrelay-emulator}"
PKG="${PKG:-dev.sshrelay}"
TEST_PKG="${TEST_PKG:-dev.sshrelay.test}"
RUNNER="${RUNNER:-androidx.test.runner.AndroidJUnitRunner}"
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

for f in "$APP_APK" "$TEST_APK"; do
  [ -f "$f" ] || { echo "missing $f - run: ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest" >&2; exit 1; }
done

if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTAINER_NAME"; then
  adb_in() {
    docker exec -e "HOME=$SSHRELAY_TOOLCHAIN/adb-home" -u "$(id -u):$(id -g)" \
      "$CONTAINER_NAME" "$ANDROID_HOME/platform-tools/adb" "$@"
  }
else
  adb_in() { adb "$@"; }
fi

adb_in start-server >/dev/null 2>&1 || true
if ! adb_in devices | grep -qw device; then
  echo "no device attached; run: scripts/emulator.sh start" >&2
  exit 1
fi

CLASS_FILTER="${1:-}"
[ -n "$CLASS_FILTER" ] && shift || true

echo "device: $(adb_in devices | awk '/device$/{print $1; exit}')"
[ -n "$CLASS_FILTER" ] && echo "class:  $CLASS_FILTER"
echo "installing APKs ..."
adb_in install -r -t "$APP_APK" >/dev/null
adb_in install -r -t "$TEST_APK" >/dev/null
echo "running ..."
echo

CMD=(shell am instrument -w)
[ -n "$CLASS_FILTER" ] && CMD+=(-e class "$CLASS_FILTER")
CMD+=("$@")
CMD+=("$TEST_PKG/$RUNNER")

set +e
OUTPUT="$(adb_in "${CMD[@]}" 2>&1)"
STATUS=$?
set -e
echo "$OUTPUT"

# `am instrument -w` exits 0 even when tests fail, so the verdict has to come from the
# runner's own summary ("OK (n tests)" vs "FAILURES!!!").
if echo "$OUTPUT" | grep -q "FAILURES!!!" || echo "$OUTPUT" | grep -q "INSTRUMENTATION_FAILED"; then
  echo
  echo "instrumentation reported failures" >&2
  exit 1
fi
if echo "$OUTPUT" | grep -qE "^OK \([0-9]+ test"; then
  echo
  echo "$(echo "$OUTPUT" | grep -E '^OK \([0-9]+ test' | tail -1)"
  exit 0
fi

echo
echo "could not determine the result from the runner output (adb exit $STATUS)" >&2
exit "$STATUS"
