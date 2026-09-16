#!/usr/bin/env bash
# Run the instrumented tests against the emulator.
#
#   scripts/connected-test.sh                                   # every class
#   scripts/connected-test.sh dev.sshrelay.TunnelEndToEndTest    # one class
#
# Two things make this more than a plain `./gradlew :app:connectedDebugAndroidTest`:
#
# 1. adb. AGP spawns the *real* adb binary from platform-tools (not scripts/adb), and
#    adb aborts with
#        Cannot mkdir '/home/lzh/.android': Permission denied
#    when it cannot create `$HOME/.android` — and `$HOME` is not writable on this box.
#    HOME is overridden for the build only, and `--no-daemon` guarantees the override
#    actually reaches the adb child (a reused daemon keeps its original environment).
#
# 2. Where the build runs. The emulator lives in a container (Docker Desktop, so a
#    separate VM namespace), and its adb server only sees the device there. Rather
#    than proxying adb across two network namespaces — which works but is fiddly and
#    was observed to hand back an empty device list — the test build runs *inside that
#    same container*. JDK, Android SDK, Gradle and its cache are all bind-mounted at
#    identical paths, so the build is byte-for-byte the same; only the adb hop
#    disappears.
#
# Memory: this box has 7.6GB and the emulator takes ~2.5GB. One modestly sized build
# JVM (no Gradle daemon, no separate Kotlin daemon) is what keeps the guest from
# thrashing; a run without these settings ended with the emulator OOM-killed mid-suite.
#
# An optional class-name filter runs a single test class: shorter runs, lower peak
# memory, and a crash costs one class instead of the whole suite.
#
# Prerequisites:
#   scripts/emulator.sh start
#   scripts/run-test-sshd.sh container     # the far end must live next to the guest
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

CONTAINER_NAME="${CONTAINER_NAME:-sshrelay-emulator}"
FILTER="${1:-}"
shift || true

GRADLE_ARGS=(--no-daemon
  -Dkotlin.compiler.execution.strategy=in-process
  -Dorg.gradle.workers.max=2
  :app:connectedDebugAndroidTest
  --console=plain)
if [ -n "$FILTER" ]; then
  GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=$FILTER")
fi
# AGP uninstalls the app when the run finishes, which also deletes any screenshot the
# tests wrote into the app's storage. Set KEEP_INSTALLED=1 to pull evidence afterwards
# (see scripts/pull-evidence.sh).
if [ "${KEEP_INSTALLED:-0}" = "1" ]; then
  GRADLE_ARGS+=("-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true")
fi

# Free the daemons first: they would otherwise sit on ~2.5GB during the run.
"$ROOT/gradlew" --stop >/dev/null 2>&1 || true

in_container=false
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTAINER_NAME"; then
  in_container=true
fi

if [ "$in_container" = true ]; then
  echo "emulator:   container '$CONTAINER_NAME'"
  [ -n "$FILTER" ] && echo "filter:     $FILTER"
  exec docker exec \
    -u "$(id -u):$(id -g)" \
    -e "HOME=$SSHRELAY_TOOLCHAIN/adb-home" \
    -e "JAVA_HOME=$JAVA_HOME" \
    -e "PATH=$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin" \
    -e "ANDROID_HOME=$ANDROID_HOME" \
    -e "ANDROID_SDK_ROOT=$ANDROID_HOME" \
    -e "ANDROID_USER_HOME=$ANDROID_USER_HOME" \
    -e "ANDROID_AVD_HOME=$ANDROID_AVD_HOME" \
    -e "ANDROID_EMULATOR_HOME=$ANDROID_EMULATOR_HOME" \
    -e "GRADLE_USER_HOME=$GRADLE_USER_HOME" \
    -e "GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8" \
    -w "$ROOT" \
    "$CONTAINER_NAME" \
    "$ROOT/gradlew" "${GRADLE_ARGS[@]}" "$@"
else
  echo "emulator:   adb over ${ADB_SERVER_SOCKET:-local server}"
  [ -n "$FILTER" ] && echo "filter:     $FILTER"
  if ! adb devices 2>/dev/null | grep -qw "device"; then
    echo "no emulator attached to adb; run: scripts/emulator.sh start" >&2
    exit 1
  fi
  mkdir -p "$SSHRELAY_TOOLCHAIN/adb-home"
  export HOME="$SSHRELAY_TOOLCHAIN/adb-home"
  export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"
  cd "$ROOT"
  exec "$ROOT/gradlew" "${GRADLE_ARGS[@]}" "$@"
fi
