#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

runtime_hits="$({
  rg -n -i 'mpv|dev\.jdtech|set_engine' \
    android-shared/src/androidMain \
    androidApp/src/androidMain \
    androidTvApp/src/androidMain \
    shared/src/commonMain \
    gradle/libs.versions.toml \
    android-shared/build.gradle.kts \
    proguard-rules.pro || true
} | rg -v 'PlaybackModels\.kt|PlaybackProtocolV3\.kt' || true)"

if [[ -n "$runtime_hits" ]]; then
  echo "Unexpected MPV runtime reference(s):" >&2
  echo "$runtime_hits" >&2
  exit 1
fi

for apk in "$@"; do
  [[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
  if unzip -l "$apk" | rg -i 'libmpv|jdtech|/mpv/' >/dev/null; then
    echo "MPV artifact found in $apk" >&2
    unzip -l "$apk" | rg -i 'libmpv|jdtech|/mpv/' >&2
    exit 1
  fi
done

echo "Media3-only runtime check passed."
