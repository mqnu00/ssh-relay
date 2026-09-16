#!/usr/bin/env bash
# Start / stop the user-space test SSH server (tools/test-sshd).
#
# This box has no root, so openssh-server cannot be installed; the "remote host"
# is a small JVM process instead. It listens on 0.0.0.0:2222 for SSH and serves
# the acceptance page on 127.0.0.1:18080, which is only reachable *through* the
# tunnel.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

STATE="$SSHRELAY_TOOLCHAIN/test-sshd"
PIDFILE="$STATE/server.pid"
LOGFILE="$STATE/server.log"
DIST="$ROOT/tools/test-sshd/build/install/test-sshd/bin/test-sshd"

# Emulator container name (scripts/emulator.sh). When the emulator runs inside it,
# 10.0.2.2 inside the guest resolves to that container's loopback -- so the "remote
# host" has to live in the same container, not in WSL.
CONTAINER_NAME="${CONTAINER_NAME:-sshrelay-emulator}"
CONTAINER_LOG="$STATE/container.log"

container_running() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTAINER_NAME"
}

# Start the server inside the emulator container. Used by the instrumented tests.
start_in_container() {
  if ! container_running; then
    echo "container '$CONTAINER_NAME' is not running; start the emulator first" >&2
    exit 1
  fi

  mkdir -p "$STATE"
  echo "building test-sshd ..."
  (cd "$ROOT" && "$ROOT/gradlew" -q :tools:test-sshd:installDist)

  if docker exec "$CONTAINER_NAME" pgrep -f "testsshd.MainKt" >/dev/null 2>&1; then
    echo "test sshd already running inside $CONTAINER_NAME"
    return 0
  fi

  echo "starting test sshd inside $CONTAINER_NAME (reachable as 10.0.2.2 from the guest)"
  : >"$CONTAINER_LOG"
  docker exec -d \
    -u "$(id -u):$(id -g)" \
    -e "HOME=$SSHRELAY_TOOLCHAIN/adb-home" \
    -e "JAVA_HOME=$JAVA_HOME" \
    -e "PATH=$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin" \
    -e "HOST_KEY_PATH=$STATE/hostkey.ser" \
    -w "$STATE" \
    "$CONTAINER_NAME" \
    bash -c "exec '$DIST' >>'$CONTAINER_LOG' 2>&1"

  for _ in $(seq 1 60); do
    if grep -q "test SSH server ready" "$CONTAINER_LOG" 2>/dev/null; then
      sed -n '/test SSH server ready/,/====$/p' "$CONTAINER_LOG"
      return 0
    fi
    sleep 0.5
  done

  echo "test sshd did not become ready inside the container; log:" >&2
  tail -40 "$CONTAINER_LOG" >&2
  exit 1
}

stop_in_container() {
  docker exec "$CONTAINER_NAME" pkill -f "testsshd.MainKt" >/dev/null 2>&1 || true
  echo "stopped (container)"
}

start() {
  if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "test sshd already running (pid $(cat "$PIDFILE"))"
    return 0
  fi

  mkdir -p "$STATE"
  echo "building test-sshd ..."
  (cd "$ROOT" && "$ROOT/gradlew" -q :tools:test-sshd:installDist)

  echo "starting test sshd ..."
  (
    cd "$STATE"
    HOST_KEY_PATH="$STATE/hostkey.ser" nohup "$DIST" >"$LOGFILE" 2>&1 &
    echo $! >"$PIDFILE"
  )

  for _ in $(seq 1 40); do
    if grep -q "test SSH server ready" "$LOGFILE" 2>/dev/null; then
      sed -n '/test SSH server ready/,/====$/p' "$LOGFILE"
      return 0
    fi
    sleep 0.5
  done

  echo "test sshd did not become ready; log:" >&2
  tail -40 "$LOGFILE" >&2
  exit 1
}

stop() {
  if [ -f "$PIDFILE" ]; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    rm -f "$PIDFILE"
  fi
  echo "stopped"
}

status() {
  if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "running in WSL (pid $(cat "$PIDFILE"))"
    grep -E "SSH   :|HTTP  :" "$LOGFILE" || true
  else
    echo "not running in WSL"
  fi
  if container_running && docker exec "$CONTAINER_NAME" pgrep -f "testsshd.MainKt" >/dev/null 2>&1; then
    echo "running in container '$CONTAINER_NAME' (reachable as 10.0.2.2 from the guest)"
    grep -E "SSH   :|HTTP  :" "$CONTAINER_LOG" 2>/dev/null || true
  fi
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  # The instrumented tests need the far end inside the emulator container.
  container) shift; start_in_container "$@" ;;
  container-stop) stop_in_container ;;
  *)
    echo "usage: $0 {start|stop|restart|status|container|container-stop}" >&2
    echo "  start               run in WSL (used by scripts/verify-remote-forwarding.sh)" >&2
    echo "  container           run inside the emulator container (used by connectDebugAndroidTest)" >&2
    exit 2
    ;;
esac
