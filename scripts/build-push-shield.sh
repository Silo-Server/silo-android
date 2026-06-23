#!/usr/bin/env bash
#
# build-push-shield.sh — Build the Android TV app and install it on an Nvidia Shield over ADB.
#
# Defaults:
#   host: none; set SHIELD_HOST or pass --host
#   port: 5555
#   variant: debug universal APK
#
# Usage:
#   SHIELD_HOST=192.0.2.10 bash scripts/build-push-shield.sh
#   bash scripts/build-push-shield.sh --host 192.0.2.10
#   bash scripts/build-push-shield.sh --skip-build

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

DEFAULT_HOST=${SHIELD_HOST:-}
DEFAULT_PORT=${SHIELD_PORT:-5555}

HOST="$DEFAULT_HOST"
PORT="$DEFAULT_PORT"
SKIP_BUILD=0

APK_PATH="$REPO_ROOT/androidTvApp/build/outputs/apk/debug/androidTvApp-universal-debug.apk"
GRADLE_TASK=":androidTvApp:assembleDebug"
APP_ID="com.silo.app.tv"

log() { printf "\n\033[1;34m[build-push-shield]\033[0m %s\n" "$*"; }
die() { printf "\n\033[1;31m[build-push-shield] ERROR:\033[0m %s\n" "$*" >&2; exit 1; }

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Build the Android TV debug APK and install it on an Nvidia Shield over ADB.

Options:
  --host IP         Shield IP address (default: ${DEFAULT_HOST:-none})
  --port PORT       ADB TCP port (default: $DEFAULT_PORT)
  --skip-build      Reuse the existing APK at:
                    $APK_PATH
  -h, --help        Show this help

Environment overrides:
  SHIELD_HOST       Default Shield IP
  SHIELD_PORT       Default ADB TCP port
  JAVA_HOME         Preferred Java home (must be Java 21+)
  ADB               Explicit adb binary path
  ANDROID_HOME      Android SDK root
  ANDROID_SDK_ROOT  Android SDK root
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host)
            [[ $# -ge 2 ]] || die "--host requires a value"
            HOST="$2"
            shift 2
            ;;
        --port)
            [[ $# -ge 2 ]] || die "--port requires a value"
            PORT="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "Unknown argument: $1"
            ;;
    esac
done

[[ -n "$HOST" ]] || die "Set SHIELD_HOST or pass --host with the Shield IP address."

resolve_java_home() {
    local candidate

    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi

    for candidate in \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
        "/opt/homebrew/opt/openjdk@21"; do
        if [[ -x "$candidate/bin/java" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        candidate=$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)
        if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi

    return 1
}

require_java_21() {
    local java_home=$1
    local java_version

    java_version=$("$java_home/bin/java" -version 2>&1 | head -1)
    case "$java_version" in
        *\"21.*|*\"22.*|*\"23.*|*\"24.*|*\"25.*) ;;
        *) die "JAVA_HOME ($java_home) must be Java 21+. Got: $java_version" ;;
    esac
}

resolve_sdk_root() {
    local candidate
    local local_properties_sdk

    if [[ -f "$REPO_ROOT/local.properties" ]]; then
        local_properties_sdk=$(sed -n 's/^sdk.dir=//p' "$REPO_ROOT/local.properties" | tail -1)
        if [[ -n "$local_properties_sdk" && -d "$local_properties_sdk" ]]; then
            printf '%s\n' "$local_properties_sdk"
            return 0
        fi
    fi

    for candidate in \
        "${ANDROID_HOME:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "/opt/homebrew/share/android-commandlinetools"; do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

resolve_adb() {
    local sdk_root=$1

    if [[ -n "${ADB:-}" && -x "${ADB}" ]]; then
        printf '%s\n' "$ADB"
        return 0
    fi

    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return 0
    fi

    if [[ -x "$sdk_root/platform-tools/adb" ]]; then
        printf '%s\n' "$sdk_root/platform-tools/adb"
        return 0
    fi

    return 1
}

ensure_device_connected() {
    local adb_bin=$1
    local serial=$2

    "$adb_bin" start-server >/dev/null

    if "$adb_bin" devices | awk 'NR>1 {print $1}' | grep -Fxq "$serial"; then
        log "ADB device already connected: $serial"
        return 0
    fi

    log "Connecting to $serial"
    "$adb_bin" connect "$serial" >/dev/null || true

    if ! "$adb_bin" devices | awk 'NR>1 {print $1}' | grep -Fxq "$serial"; then
        die "Could not reach $serial over ADB. Make sure the Shield is awake, on the same network, and has network debugging enabled."
    fi
}

JAVA_HOME_RESOLVED=$(resolve_java_home) || die "Could not resolve JAVA_HOME automatically. Export JAVA_HOME pointing to Java 21."
require_java_21 "$JAVA_HOME_RESOLVED"

SDK_ROOT=$(resolve_sdk_root) || die "Could not resolve Android SDK root. Set ANDROID_HOME or fix local.properties."
ADB_BIN=$(resolve_adb "$SDK_ROOT") || die "Could not find adb. Install Android platform-tools or set ADB=/absolute/path/to/adb."
SERIAL="${HOST}:${PORT}"

log "Repo root: $REPO_ROOT"
log "Java:      $JAVA_HOME_RESOLVED"
log "SDK:       $SDK_ROOT"
log "ADB:       $ADB_BIN"
log "Target:    $SERIAL"

ensure_device_connected "$ADB_BIN" "$SERIAL"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    log "Building Android TV APK via $GRADLE_TASK"
    (
        cd "$REPO_ROOT"
        JAVA_HOME="$JAVA_HOME_RESOLVED" ./gradlew "$GRADLE_TASK"
    )
else
    log "Skipping build; reusing existing APK"
fi

[[ -f "$APK_PATH" ]] || die "Expected APK not found at $APK_PATH"

log "Installing APK"
"$ADB_BIN" -s "$SERIAL" install -r "$APK_PATH"

log "Verifying package install"
"$ADB_BIN" -s "$SERIAL" shell pm list packages | grep -Fq "$APP_ID" || die "Install finished, but $APP_ID was not reported by pm list packages."

log "Done"
printf "Installed %s on %s\n" "$APP_ID" "$SERIAL"
