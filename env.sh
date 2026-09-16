#!/usr/bin/env bash
# Source this file to get the workspace-local Android toolchain.
#   source env.sh
#
# Everything lives under .toolchain/ on purpose: this WSL box has no root,
# $HOME is not writable, and dnf is unusable (CentOS Stream 8 EOL).
# All tools are plain extracted archives -> fully user-space, fully reproducible.

SSHRELAY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SSHRELAY_ROOT
export SSHRELAY_TOOLCHAIN="$SSHRELAY_ROOT/.toolchain"

export JAVA_HOME="$SSHRELAY_TOOLCHAIN/jdk17"
export ANDROID_HOME="$SSHRELAY_TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$SSHRELAY_TOOLCHAIN/android-home"   # keeps ~/.android out of the picture
export ANDROID_AVD_HOME="$SSHRELAY_TOOLCHAIN/avd"
export ANDROID_EMULATOR_HOME="$SSHRELAY_TOOLCHAIN/emulator-home"
export GRADLE_USER_HOME="$SSHRELAY_TOOLCHAIN/gradle-home"
export GRADLE_DIST="$SSHRELAY_TOOLCHAIN/gradle-8.9"

# Deterministic, memory-frugal JVM defaults (WSL has 7.6GB total, ~4GB free).
if [ -z "${GRADLE_OPTS:-}" ]; then
  export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8"
fi

# scripts/ comes first so that scripts/adb (a wrapper working around the
# non-writable $HOME) wins over the real binary from platform-tools.
export PATH="$SSHRELAY_ROOT/scripts:$JAVA_HOME/bin:$GRADLE_DIST/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Libraries the emulator needs that this rootless CentOS Stream 8 image lacks
# (libpulse / libxkbfile / libasyncns / libsndfile). scripts/fetch-system-libs.sh
# extracts them from EL8 RPMs into .toolchain/sysroot; libpulsecommon lives in the
# pulseaudio subdirectory rather than lib64.
if [ -d "$SSHRELAY_TOOLCHAIN/sysroot/usr/lib64" ]; then
  export LD_LIBRARY_PATH="$SSHRELAY_TOOLCHAIN/sysroot/usr/lib64:$SSHRELAY_TOOLCHAIN/sysroot/usr/lib64/pulseaudio:${LD_LIBRARY_PATH:-}"
fi

mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$ANDROID_EMULATOR_HOME" "$GRADLE_USER_HOME" 2>/dev/null || true

# Quick sanity report when sourced interactively
if [ -n "${PS1:-}" ]; then
  echo "ssh-relay env:"
  echo "  JAVA_HOME    = $JAVA_HOME  ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1 || echo 'not installed yet'))"
  echo "  ANDROID_HOME = $ANDROID_HOME"
  echo "  GRADLE_USER_HOME = $GRADLE_USER_HOME"
fi

# When the emulator runs inside a container, adb lives there too and the host talks to
# it over TCP (see scripts/container-entrypoint.sh). scripts/emulator.sh writes this
# file on start and removes it on stop.
if [ -f "$SSHRELAY_TOOLCHAIN/adb-endpoint.env" ]; then
  # shellcheck disable=SC1091
  . "$SSHRELAY_TOOLCHAIN/adb-endpoint.env"
  export ADB_SERVER_SOCKET ANDROID_ADB_SERVER_PORT
fi
