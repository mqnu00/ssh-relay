#!/usr/bin/env bash
# Manage the API 35 (Android 15) AVD used for verification.
#
#   scripts/emulator.sh accel    # Gate 0: is KVM usable?
#   scripts/emulator.sh create   # create the AVD (idempotent)
#   scripts/emulator.sh start    # headless boot, waits for sys.boot_completed
#   scripts/emulator.sh stop
#   scripts/emulator.sh status
#   scripts/emulator.sh log
#
# The emulator runs inside a container (docker/emulator.Dockerfile) rather than
# directly on this host: CentOS Stream 8 ships glibc 2.28, while the emulator's
# bundled Qt6 libraries require GLIBC_2.29/2.30. The container has glibc 2.35 *and*
# root, which fixes both the glibc gap and the runtime libraries the emulator needs.
# It runs with --network host, so adb and Gradle stay in WSL and see the emulator on
# localhost exactly as if it were native.
#
# Headless throughout: screenshots come from `adb exec-out screencap`, so nothing
# needs to be rendered to a display.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

AVD_NAME="${AVD_NAME:-sshrelay_api35}"
EMULATOR="$ANDROID_HOME/emulator/emulator"
# The real binary, for use *inside* the container (scripts/adb is a host-side wrapper).
ADB_BIN="$ANDROID_HOME/platform-tools/adb"
AVD_INI="$ANDROID_AVD_HOME/$AVD_NAME.ini"
AVD_DIR="$ANDROID_AVD_HOME/$AVD_NAME.avd"
LOG="$SSHRELAY_TOOLCHAIN/emulator.log"
RAM_MB="${RAM_MB:-2048}"
CORES="${CORES:-4}"

IMAGE_TAG="${IMAGE_TAG:-ssh-relay-emulator:latest}"
CONTAINER_NAME="${CONTAINER_NAME:-sshrelay-emulator}"
DOCKERFILE="$ROOT/docker/emulator.Dockerfile"
# The host-side adb wrapper uses this as HOME (see scripts/adb).
ADB_HOME="$SSHRELAY_TOOLCHAIN/adb-home"
# Host-side port that proxies to the container's adb server.
ADB_PROXY_PORT="${ADB_PROXY_PORT:-15037}"
ADB_ENDPOINT_FILE="$SSHRELAY_TOOLCHAIN/adb-endpoint.env"
# The emulator keeps its own adb identity at $ANDROID_EMULATOR_HOME/adbkey — it does
# *not* read it from HOME — and pushes that public key into the guest at boot. Unless
# the host adb server presents the very same key, `adb` reports the device as
# `offline` forever.
CONTAINER_HOME="$ADB_HOME"

sync_adb_key() {
  local src="$ANDROID_EMULATOR_HOME/adbkey"
  local dst="$ADB_HOME/.android/adbkey"
  mkdir -p "$ADB_HOME/.android"
  [ -f "$src" ] || return 1
  if [ -f "$dst" ] && cmp -s "$src" "$dst"; then
    return 0
  fi
  cp "$src" "$dst"
  chmod 600 "$dst"
  # Drop a stale public half so adb derives it from the private key we just installed.
  rm -f "$ADB_HOME/.android/adbkey.pub"
  adb kill-server >/dev/null 2>&1 || true
  echo "   synced adb identity from $src"
  return 0
}

image_path() {
  local recorded
  recorded="$(sed -n 's/^IMAGE=//p' "$SSHRELAY_TOOLCHAIN/system-image.env" 2>/dev/null | head -1)"
  if [ -n "$recorded" ]; then
    echo "$recorded"
    return
  fi
  # Fallback: derive it from what is actually on disk, in case the env file is
  # missing (e.g. the SDK was installed by another tool).
  local dir
  dir="$(find "$ANDROID_HOME/system-images" -mindepth 3 -maxdepth 3 -type d 2>/dev/null | head -1)"
  if [ -n "$dir" ]; then
    local rel="${dir#"$ANDROID_HOME"/system-images/}"
    echo "system-images;$(echo "$rel" | tr '/' ';')"
  fi
}

# Can the emulator run directly on the host? If its Qt libraries need a newer glibc
# than this distro has, the dynamic linker fails immediately.
needs_container() {
  ! "$EMULATOR" -version >/dev/null 2>&1
}

ensure_image() {
  docker image inspect "$IMAGE_TAG" >/dev/null 2>&1 && return 0
  echo "building emulator container image ($IMAGE_TAG) ..."
  docker build -t "$IMAGE_TAG" -f "$DOCKERFILE" "$ROOT/docker"
}

docker_run_args() {
  mkdir -p "$CONTAINER_HOME"
  printf '%s\n' \
    --name "$CONTAINER_NAME" \
    `# The emulator binds 5554/5555 to the container's own loopback, so those ports` \
    `# cannot be published. Instead the container's adb server (bound with -a) is` \
    `# published, and the host talks to it via ADB_SERVER_SOCKET.` \
    -p "$ADB_PROXY_PORT:5037" \
    --device /dev/kvm \
    `# QEMU needs a larger /dev/shm than Docker's 64MB default` \
    --shm-size=2g \
    `# Docker's default seccomp profile returns EPERM for clone3, which QEMU surfaces` \
    `# as "qemu_thread_create: Operation not permitted"` \
    --security-opt seccomp=unconfined \
    -v "$SSHRELAY_TOOLCHAIN:$SSHRELAY_TOOLCHAIN" \
    -v "$ROOT:$ROOT" \
    -u "$(id -u):$(id -g)" \
    -e "HOME=$CONTAINER_HOME" \
    -e "ANDROID_HOME=$ANDROID_HOME" \
    -e "ANDROID_SDK_ROOT=$ANDROID_HOME" \
    -e "ANDROID_AVD_HOME=$ANDROID_AVD_HOME" \
    -e "ANDROID_EMULATOR_HOME=$ANDROID_EMULATOR_HOME" \
    -e "ANDROID_USER_HOME=$ANDROID_USER_HOME" \
    -w "$ROOT" \
    "$IMAGE_TAG"
}

cmd_accel() {
  echo "== host =="
  echo "   $(ldd --version 2>/dev/null | head -1)"
  if needs_container; then
    echo "   emulator cannot run natively here -> container is used"
  else
    echo "   emulator runs natively"
  fi

  ensure_image
  echo
  echo "== emulator -accel-check (inside the container) =="
  # shellcheck disable=SC2046
  docker run $(docker_run_args) "$EMULATOR" -accel-check || true
}

cmd_create() {
  local image
  image="$(image_path)"
  if [ -z "$image" ]; then
    echo "system image unknown; run: python3 scripts/provision.py sdk" >&2
    exit 1
  fi

  if [ -f "$AVD_INI" ]; then
    echo "AVD '$AVD_NAME' already exists"
  else
    echo "creating AVD '$AVD_NAME' from $image"
    echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
      --name "$AVD_NAME" \
      --package "$image" \
      --force
  fi

  # Patch the hardware profile for a memory-tight, headless host (7.6GB total).
  python3 - "$AVD_DIR/config.ini" "$RAM_MB" "$CORES" <<'PY'
import sys, pathlib
cfg, ram, cores = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
overrides = {
    "hw.ramSize": ram,
    "vm.heapSize": "256",
    "hw.cpu.ncore": cores,
    "hw.gpu.enabled": "yes",
    "hw.gpu.mode": "swiftshader_indirect",
    "hw.audioInput": "no",
    "hw.audioOutput": "no",
    "hw.keyboard": "yes",
    "disk.dataPartition.size": "6442450944",
    # A small framebuffer on purpose: this host has 7.6GB and the guest's surface
    # flinger/framebuffer cost matters more than pixel fidelity. Screenshots are
    # still perfectly readable.
    "hw.lcd.density": "320",
    "hw.lcd.width": "720",
    "hw.lcd.height": "1280",
    "showDeviceFrame": "no",
}
lines = cfg.read_text().splitlines() if cfg.exists() else []
seen, out = set(), []
for line in lines:
    key = line.split("=", 1)[0] if "=" in line else None
    if key in overrides:
        out.append(f"{key}={overrides[key]}")
        seen.add(key)
    else:
        out.append(line)
for key, value in overrides.items():
    if key not in seen:
        out.append(f"{key}={value}")
cfg.write_text("\n".join(out) + "\n")
print(f"patched {cfg}: ram={ram}MB cores={cores} gpu=swiftshader_indirect")
PY
}

cmd_start() {
  if adb devices 2>/dev/null | grep -q "emulator-.*device"; then
    echo "an emulator is already running:"
    adb devices
    return 0
  fi
  [ -f "$AVD_INI" ] || { echo "AVD '$AVD_NAME' does not exist; run: $0 create" >&2; exit 1; }

  local launch=(
    "$EMULATOR" -avd "$AVD_NAME"
    -no-window
    -no-audio
    -no-boot-anim
    -no-snapshot
    -no-metrics
    -gpu swiftshader_indirect
    -memory "$RAM_MB"
    -cores "$CORES"
    -netdelay none -netspeed full
  )
  # The emulator refuses to go below ~2560MB of guest RAM whatever `-memory` says, so
  # on a memory-tight host the only remaining lever is to make the *guest* spend less:
  # `-lowram` sets ro.config.low_ram=true and Android trims its own footprint. It costs
  # some guest performance but keeps the host from OOM-killing the emulator.
  if [ "${LOWRAM:-0}" = "1" ]; then
    launch+=(-lowram)
  fi

  mkdir -p "$(dirname "$LOG")"

  # A run that was killed (timeout, CI abort, OOM) leaves lock files behind and the
  # next start then refuses with "Running multiple emulators with the same AVD".
  # Only clear them when nothing is actually running.
  #
  # Any *other* container of this image must go too: it would still hold the AVD's
  # qcow2 images and the next start would die with
  #   "Failed to get write lock ... Is another process using the image?"
  docker ps -q --filter "ancestor=$IMAGE_TAG" 2>/dev/null | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  find "$AVD_DIR" -maxdepth 1 -name "*.lock" -delete 2>/dev/null || true

  if needs_container; then
    ensure_image
    docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    echo "starting AVD '$AVD_NAME' headless in container (ram=${RAM_MB}MB)"
    # Run in the foreground under nohup (not `docker run -d`) so the whole emulator
    # output lands in $LOG: a detached container that dies would take its logs with
    # it, which is exactly what makes emulator failures hard to diagnose.
    #
    # The entrypoint starts the container's adb server on 0.0.0.0 before the emulator
    # boots, so the emulator registers with it and we can reach that server through
    # the published port.
    # shellcheck disable=SC2046
    nohup docker run $(docker_run_args) \
      "$ROOT/scripts/container-entrypoint.sh" "$ADB_BIN" "${launch[@]}" >"$LOG" 2>&1 &
  else
    echo "starting AVD '$AVD_NAME' headless natively (ram=${RAM_MB}MB)"
    nohup "${launch[@]}" >"$LOG" 2>&1 &
  fi
  echo "   log: $LOG"

  if needs_container; then
    echo "waiting for the container's adb server ..."
    # Point every subsequent adb call (and, through env.sh, Gradle/AGP) at the adb
    # server inside the container.
    local endpoint_written=0
    for _ in $(seq 1 60); do
      if (exec 3<>/dev/tcp/127.0.0.1/"$ADB_PROXY_PORT") 2>/dev/null; then
        # Both variables are needed:
        #   ADB_SERVER_SOCKET        - honoured by the adb CLI
        #   ANDROID_ADB_SERVER_PORT  - honoured by ddmlib, which is what AGP uses;
        #                              ddmlib ignores ADB_SERVER_SOCKET and would
        #                              otherwise dial 127.0.0.1:5037 and report
        #                              "No connected devices!"
        {
          printf 'ADB_SERVER_SOCKET=tcp:127.0.0.1:%s\n' "$ADB_PROXY_PORT"
          printf 'ANDROID_ADB_SERVER_PORT=%s\n' "$ADB_PROXY_PORT"
        } >"$ADB_ENDPOINT_FILE"
        export ADB_SERVER_SOCKET="tcp:127.0.0.1:$ADB_PROXY_PORT"
        export ANDROID_ADB_SERVER_PORT="$ADB_PROXY_PORT"
        endpoint_written=1
        break
      fi
      sleep 3
    done
    if [ "$endpoint_written" -eq 0 ]; then
      echo "the container's adb server never became reachable on port $ADB_PROXY_PORT; log:" >&2
      cmd_log | tail -30 >&2
      exit 1
    fi
    echo "   ADB_SERVER_SOCKET=$ADB_SERVER_SOCKET"
  fi

  echo "waiting for the device ..."
  local connected=0
  for _ in $(seq 1 80); do
    if [ "$(adb get-state 2>/dev/null | tr -d '\r')" = "device" ]; then
      connected=1
      break
    fi
    sleep 3
  done
  if [ "$connected" -eq 0 ]; then
    echo "the emulator never reached state 'device'; adb devices:" >&2
    adb devices >&2 || true
    cmd_log | tail -30 >&2
    exit 1
  fi

  echo "waiting for boot to complete ..."
  local waited=0
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 5
    waited=$((waited + 5))
    if [ "$waited" -ge 900 ]; then
      echo "boot did not complete within 900s; last log lines:" >&2
      cmd_log | tail -30 >&2
      exit 1
    fi
  done

  adb shell input keyevent 82 >/dev/null 2>&1 || true
  echo
  echo "emulator ready:"
  adb devices
  echo "android $(adb shell getprop ro.build.version.release | tr -d '\r')  api $(adb shell getprop ro.build.version.sdk | tr -d '\r')  abi $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
}

cmd_log() {
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTAINER_NAME"; then
    docker logs "$CONTAINER_NAME" 2>&1
  elif [ -f "$LOG" ]; then
    cat "$LOG"
  else
    echo "(no emulator log yet)"
  fi
}

cmd_stop() {
  adb emu kill 2>/dev/null || true
  sleep 2
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  docker ps -q --filter "ancestor=$IMAGE_TAG" 2>/dev/null | xargs -r docker rm -f >/dev/null 2>&1 || true
  rm -f "$ADB_ENDPOINT_FILE"
  find "$AVD_DIR" -maxdepth 1 -name "*.lock" -delete 2>/dev/null || true
  echo "stopped"
}

cmd_status() {
  adb devices
  echo "boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  docker ps -a --filter "name=$CONTAINER_NAME" --format '   container: {{.Names}} {{.Status}}' 2>/dev/null || true
}

case "${1:-}" in
  accel) cmd_accel ;;
  create) cmd_create ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  log) cmd_log ;;
  *)
    echo "usage: $0 {accel|create|start|stop|status|log}" >&2
    exit 2
    ;;
esac
