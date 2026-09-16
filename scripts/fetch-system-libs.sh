#!/usr/bin/env bash
# Provide the handful of *system* libraries the Android emulator needs, without root.
#
# This box is a CentOS Stream 8 WSL image with no sudo and an EOL package set, so
# `dnf install` is not an option. Instead: pull the RPMs from AlmaLinux 8 (a binary
# compatible EL8 rebuild) and unpack only their files into .toolchain/sysroot, which
# env.sh puts on LD_LIBRARY_PATH.
#
# Only these are genuinely missing for the emulator; everything else it ships
# itself under emulator/lib64 (resolved by its own RPATH):
#   libpulse.so.0   <- pulseaudio-libs   (emulator always links it, even with -no-audio)
#   libxkbfile.so.1 <- libxkbfile
#
# Add entries to PKG_FOR_LIB below if a future emulator build needs more.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

SYSROOT="$SSHRELAY_TOOLCHAIN/sysroot"
DL="$SSHRELAY_TOOLCHAIN/downloads"
EMU="$ANDROID_HOME/emulator"
mkdir -p "$SYSROOT" "$DL"

MIRROR="${MIRROR:-https://repo.almalinux.org/almalinux/8}"
REPOS=(BaseOS AppStream)

declare -A PKG_FOR_LIB=(
  [libpulse.so.0]=pulseaudio-libs
  [libxkbfile.so.1]=libxkbfile
  # transitive dependencies pulled in by the two above
  [libasyncns.so.0]=libasyncns
  [libsndfile.so.1]=libsndfile
  [libFLAC.so.8]=flac-libs
  [libgsm.so.1]=gsm
  [libogg.so.0]=libogg
  [libvorbis.so.0]=libvorbis
  [libvorbisenc.so.2]=libvorbis
)

step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

# Our sysroot's library search path. libpulsecommon lives under a pulseaudio/
# subdirectory, not in lib64.
sysroot_lib_path() {
  echo "$SYSROOT/usr/lib64:$SYSROOT/usr/lib64/pulseaudio"
}

# Which libraries are actually missing, judged with the emulator's own search paths.
missing_libs() {
  local lp="$EMU/lib64:$EMU/lib64/qt/lib:$EMU/lib64/gles_swiftshader:$EMU/lib64/vulkan:$(sysroot_lib_path)"
  for bin in "$EMU/emulator" "$EMU/qemu/linux-x86_64/qemu-system-x86_64" "$EMU/crashpad_handler"; do
    [ -f "$bin" ] || continue
    LD_LIBRARY_PATH="$lp" ldd "$bin" 2>/dev/null | grep "not found" || true
  done | awk '{print $1}' | grep -v ':$' | sort -u
}

find_rpm_url() {
  local pkg="$1"
  for repo in "${REPOS[@]}"; do
    local base="$MIRROR/$repo/x86_64/os/Packages"
    local hit
    hit="$(curl -sS --max-time 60 "$base/" 2>/dev/null | grep -oE "${pkg}-[0-9][^\"]*\.el8[^\"]*\.x86_64\.rpm" | sort -V | tail -1)"
    if [ -n "$hit" ]; then
      echo "$base/$hit"
      return 0
    fi
  done
  return 1
}

step "missing system libraries"
mapfile -t MISSING < <(missing_libs)
if [ "${#MISSING[@]}" -eq 0 ]; then
  echo "   none - emulator can load everything it needs"
  exit 0
fi
printf '   %s\n' "${MISSING[@]}"

for lib in "${MISSING[@]}"; do
  pkg="${PKG_FOR_LIB[$lib]:-}"
  if [ -z "$pkg" ]; then
    printf '\033[1;33m   !! %s: no package mapping known, skipping\033[0m\n' "$lib"
    continue
  fi

  step "$lib  <-  $pkg"
  url="$(find_rpm_url "$pkg")" || { echo "   could not locate $pkg in the mirror"; continue; }
  rpm="$DL/$(basename "$url")"
  if [ ! -s "$rpm" ]; then
    echo "   downloading $(basename "$url")"
    curl -fL --retry 3 --retry-connrefused -o "$rpm.part" "$url" && mv "$rpm.part" "$rpm"
  else
    echo "   cached $(basename "$rpm")"
  fi

  # Extract into the local sysroot; --no-absolute-filenames keeps it sandboxed here.
  (cd "$SYSROOT" && rpm2cpio "$rpm" | cpio -idmu --quiet --no-absolute-filenames)
  echo "   extracted into $SYSROOT"
done

step "result"
find "$SYSROOT" -name "*.so*" | sed "s|$SYSROOT|   .toolchain/sysroot|" | head -20
echo
mapfile -t STILL < <(missing_libs)
if [ "${#STILL[@]}" -eq 0 ]; then
  printf '\033[1;32m   OK\033[0m all emulator dependencies now resolve\n'
else
  printf '\033[1;31m   still missing:\033[0m %s\n' "${STILL[*]}"
  exit 1
fi
