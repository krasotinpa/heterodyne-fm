#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

python_checks() {
    python3.12 -m unittest discover -s tests/research
    python3.12 -m unittest discover -s tests/build_version
}

android_checks() {
    ./gradlew test lint assembleDebug
    sha256sum app/build/outputs/apk/debug/app-debug.apk
}

case "${1:-all}" in
    all) python_checks; android_checks ;;
    python) python_checks ;;
    android) android_checks ;;
    *) echo "Usage: $0 [all|python|android]" >&2; exit 2 ;;
esac
