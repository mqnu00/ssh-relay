#!/usr/bin/env bash
# Thin shim instead of the standard Gradle wrapper.
#
# The wrapper would re-download a ~130 MB distribution from services.gradle.org;
# this link tops out near 300 KB/s, so we use the copy already provisioned in
# .toolchain/ (scripts/provision.py) and keep every path workspace-relative.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$ROOT/env.sh" >/dev/null

GRADLE_BIN="$ROOT/.toolchain/gradle-8.9/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  echo "Gradle not provisioned yet. Run:  python3 scripts/provision.py gradle jdk" >&2
  exit 1
fi

exec "$GRADLE_BIN" "$@"
