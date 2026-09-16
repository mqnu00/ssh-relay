#!/usr/bin/env bash
# Thin wrapper: install the Android SDK components into .toolchain/android-sdk.
#
# The implementation lives in scripts/provision.py, which also fetches the JDK,
# Gradle and the command-line tools, and writes the package.xml metadata that AGP
# needs to resolve components without reaching out to the network. This entry point
# exists for when only the SDK part is wanted.
#
#   scripts/sdk-install.sh
#   SDK_MIRROR=https://mirrors.cloud.tencent.com/AndroidSDK/ scripts/sdk-install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$ROOT/scripts/provision.py" sdk pkgxml licenses
