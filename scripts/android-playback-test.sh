#!/usr/bin/env bash
#
# android-playback-test.sh — Scriptable playback testing for the Silo Android
# apps (TV and phone) over ADB.
#
# Pairs with the debug-only PlaybackDebugReceiver baked into both debug builds:
# player state comes back as one JSON line per `status` call, so a test run
# needs a handful of short commands instead of logcat scraping. Works against
# any adb-reachable device — Android TV boxes over TCP, phones over USB or
# wireless debugging.
#
# Usage:
#   scripts/android-playback-test.sh [-s SERIAL] <command> [args]
#
# Commands:
#   connect <host[:port]>       adb-connect to a device (port defaults to 5555)
#   play <contentId> [opts]     deep-link playback; opts: --type movie --file-id N --quality 720p
#   item <contentId>            open the detail screen for an item
#   status                      one-line JSON of live player state
#   wait-playing [timeoutSec]   poll until isPlaying=true (default 30s)
#   pause | resume | stop       transport commands
#   seek <seconds>              absolute seek
#   pos                         compact "position/duration state" one-liner
#   home                        press HOME (clean player teardown before a new link)
#   logs [minutes]              playback-relevant logcat from the last N minutes (default 2)
#   test <contentId> [opts]     full scenario: home -> play -> wait -> verify position advances
#   soak <contentId> <seconds> [opts]
#                               long-running health check: cold-start playback, sample
#                               every 10s, fail on crash/stall/error; report drops,
#                               rebuffers, and any fatal exceptions at the end
#   party                       Watch Party room and binding state (the "party" object
#                               of status); never tokens or tickets
#   spread <serialA> <serialB> [samples] [intervalSec]
#                               sample two devices in a Watch Party and report the
#                               inter-client position spread on the room's server clock
#                               (defaults: 10 samples, 2s apart). Ignores -s.
#
# Environment:
#   SILO_DEVICE_SERIAL   default adb serial (e.g. 192.0.2.10:5555 or a USB serial);
#                        -s overrides. SILO_TV_SERIAL is honored as a fallback.
#   ADB                  explicit adb binary (defaults to `adb` on PATH)

set -euo pipefail

APP_ID="org.siloserver.silo"
RECEIVER="$APP_ID/org.siloserver.silo.common.player.debug.PlaybackDebugReceiver"
ACTION_STATUS="org.siloserver.silo.debug.PLAYBACK_STATUS"
ACTION_COMMAND="org.siloserver.silo.debug.PLAYBACK_COMMAND"
ADB_BIN="${ADB:-adb}"
SERIAL="${SILO_DEVICE_SERIAL:-${SILO_TV_SERIAL:-}}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

if [[ "${1:-}" == "-s" ]]; then
    [[ $# -ge 2 ]] || die "-s requires a serial"
    SERIAL="$2"
    shift 2
fi

CMD="${1:-}"
[[ -n "$CMD" ]] || { sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
shift

adbx() {
    if [[ -n "$SERIAL" ]]; then
        "$ADB_BIN" -s "$SERIAL" "$@"
    else
        "$ADB_BIN" "$@"
    fi
}

# Sends a broadcast to the debug receiver and prints the JSON it returns in
# `am broadcast`'s "data=..." result line. The explicit component is required:
# implicit broadcasts never reach manifest receivers on API 26+.
debug_broadcast() {
    local out
    out=$(adbx shell am broadcast -n "$RECEIVER" "$@" 2>&1) || die "am broadcast failed: $out"
    # Result line looks like: Broadcast completed: result=0, data="{...}"
    local data
    data=$(printf '%s\n' "$out" | sed -n 's/.*data="\(.*\)".*/\1/p')
    if [[ -z "$data" ]]; then
        die "no data in broadcast result (release build, or receiver missing?): $out"
    fi
    printf '%s\n' "$data"
}

status_json() { debug_broadcast -a "$ACTION_STATUS"; }

player_command() { debug_broadcast -a "$ACTION_COMMAND" --es cmd "$@"; }

json_field() { # json_field <json> <key> — string/number/bool value or empty
    printf '%s' "$1" | sed -n "s/.*\"$2\":\"\{0,1\}\([^,\"}]*\)\"\{0,1\}[,}].*/\1/p"
}

play_uri() { # play_uri <contentId> [--type T] [--file-id N] [--quality Q]
    local content_id="$1"; shift
    local type="" file_id="" quality=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --type) type="$2"; shift 2 ;;
            --file-id) file_id="$2"; shift 2 ;;
            --quality) quality="$2"; shift 2 ;;
            *) die "unknown play option: $1" ;;
        esac
    done
    local uri="silo://play/${content_id}" sep="?"
    [[ -n "$type" ]] && { uri="${uri}${sep}type=${type}"; sep="&"; }
    [[ -n "$file_id" ]] && { uri="${uri}${sep}fileId=${file_id}"; sep="&"; }
    [[ -n "$quality" ]] && { uri="${uri}${sep}quality=${quality}"; sep="&"; }
    printf '%s' "$uri"
}

deep_link() {
    adbx shell am start -a android.intent.action.VIEW -d "'$1'" "$APP_ID" >/dev/null
    printf 'deep link: %s\n' "$1"
}

wait_playing() {
    local timeout="${1:-30}" waited=0 json
    while (( waited < timeout )); do
        json=$(status_json)
        if [[ "$(json_field "$json" isPlaying)" == "true" ]]; then
            printf '%s\n' "$json"
            return 0
        fi
        if [[ -n "$(json_field "$json" error)" || -n "$(json_field "$json" screenError)" ]]; then
            printf '%s\n' "$json"
            return 1
        fi
        sleep 2
        waited=$((waited + 2))
    done
    printf '%s\n' "${json:-{\"player\":\"none\"}}"
    return 1
}

case "$CMD" in
    connect)
        [[ $# -ge 1 ]] || die "connect needs a host"
        host="$1"
        [[ "$host" == *:* ]] || host="${host}:5555"
        "$ADB_BIN" connect "$host"
        ;;
    play)
        [[ $# -ge 1 ]] || die "play needs a contentId"
        content_id="$1"; shift
        deep_link "$(play_uri "$content_id" "$@")"
        ;;
    item)
        [[ $# -ge 1 ]] || die "item needs a contentId"
        deep_link "silo://item/$1"
        ;;
    status)
        status_json
        ;;
    party)
        status_json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(d.get("party", {"party": "none"}), sort_keys=True))'
        ;;
    spread)
        [[ $# -ge 2 ]] || die "spread needs two serials"
        serial_a="$1"; serial_b="$2"; samples="${3:-10}"; interval="${4:-2}"
        spread_sample() { # spread_sample <serial> -> one status JSON line
            SERIAL="$1" status_json
        }
        for ((i = 1; i <= samples; i++)); do
            a=$(spread_sample "$serial_a"); b=$(spread_sample "$serial_b")
            python3 - "$a" "$b" <<'PY'
import json, sys
def project(raw):
    d = json.loads(raw)
    party = d.get("party") or {}
    offset = party.get("serverOffsetMs")
    pos = d.get("screenPositionSec")
    if pos is None and d.get("positionMs") is not None:
        pos = d["positionMs"] / 1000.0
    if offset is None or pos is None:
        return None, d
    server_ms = d["sampledWallMs"] + offset
    return (pos, server_ms, bool(d.get("isPlaying"))), d
(a, da), (b, db) = project(sys.argv[1]), project(sys.argv[2])
if a is None or b is None:
    print("inconclusive: missing position or server clock on", "A" if a is None else "B")
    sys.exit(0)
# Project the earlier sample forward to the later one's server time while playing.
ref = max(a[1], b[1])
def at(sample):
    pos, t, playing = sample
    return pos + ((ref - t) / 1000.0 if playing else 0.0)
gap_ms = abs(a[1] - b[1])
spread = abs(at(a) - at(b))
print(json.dumps({
    "spreadSec": round(spread, 3),
    "sampleGapMs": gap_ms,
    "conclusive": gap_ms <= 1500,
    "a": {"pos": a[0], "playing": a[2], "party": (da.get("party") or {}).get("playbackState")},
    "b": {"pos": b[0], "playing": b[2], "party": (db.get("party") or {}).get("playbackState")},
}))
PY
            (( i < samples )) && sleep "$interval"
        done
        ;;
    wait-playing)
        wait_playing "${1:-30}"
        ;;
    pause)
        player_command pause
        ;;
    resume)
        player_command play
        ;;
    stop)
        player_command stop
        ;;
    seek)
        [[ $# -ge 1 ]] || die "seek needs seconds"
        player_command seek --el positionMs "$(( $1 * 1000 ))"
        ;;
    pos)
        json=$(status_json)
        printf '%ss/%ss %s\n' \
            "$(( $(json_field "$json" positionMs) / 1000 ))" \
            "$(( $(json_field "$json" durationMs) / 1000 ))" \
            "$(json_field "$json" state)"
        ;;
    home)
        adbx shell input keyevent 3
        ;;
    logs)
        minutes="${1:-2}"
        since=$(adbx shell "date -d '-${minutes} min' '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r') \
            || since=""
        adbx logcat -d ${since:+-t "$since"} -s SiloPlayback:V Media3Analytics:V SiloDovi:V SiloDeepLink:V
        ;;
    test)
        [[ $# -ge 1 ]] || die "test needs a contentId"
        content_id="$1"; shift
        # HOME first: back-press + an immediate new play link races player
        # teardown and silently drops the link. HOME + settle avoids it.
        adbx shell input keyevent 3
        sleep 3
        deep_link "$(play_uri "$content_id" "$@")"
        echo "waiting for playback..."
        if ! json=$(wait_playing 45); then
            printf 'FAIL: playback did not start\n%s\n' "$json"
            adbx logcat -d -s SiloPlayback:V Media3Analytics:V SiloDeepLink:V | tail -40
            exit 1
        fi
        pos1=$(json_field "$json" positionMs)
        sleep 5
        json=$(status_json)
        pos2=$(json_field "$json" positionMs)
        printf '%s\n' "$json"
        if [[ "$(json_field "$json" isPlaying)" == "true" && "$pos2" -gt "$pos1" ]]; then
            printf 'PASS: playing, position advanced %sms -> %sms\n' "$pos1" "$pos2"
        else
            printf 'FAIL: position not advancing (%sms -> %sms)\n' "$pos1" "$pos2"
            exit 1
        fi
        ;;
    soak)
        [[ $# -ge 2 ]] || die "soak needs a contentId and a duration in seconds"
        content_id="$1"; duration="$2"; shift 2
        adbx shell am force-stop "$APP_ID"
        sleep 2
        adbx logcat -c
        deep_link "$(play_uri "$content_id" "$@")"
        echo "waiting for playback..."
        if ! json=$(wait_playing 60); then
            printf 'FAIL: playback did not start\n%s\n' "$json"
            exit 1
        fi
        pid=$(adbx shell pidof "$APP_ID" | tr -d '\r')
        start_pos=$(json_field "$json" positionMs)
        last_pos=$start_pos
        last_drops=$(json_field "$json" droppedFrames); last_drops=${last_drops:-0}
        elapsed=0 rebuffers=0 stall_streak=0 max_stall_streak=0 total_new_drops=0
        fail_reason=""
        printf 'soak: %ss, sampling every 10s (pid %s)\n' "$duration" "$pid"
        while (( elapsed < duration )); do
            sleep 10; elapsed=$((elapsed + 10))
            pid_now=$(adbx shell pidof "$APP_ID" | tr -d '\r')
            if [[ -z "$pid_now" || "$pid_now" != "$pid" ]]; then
                fail_reason="process died or restarted (pid $pid -> ${pid_now:-gone})"
                break
            fi
            json=$(status_json)
            state=$(json_field "$json" state)
            err=$(json_field "$json" error)
            pos=$(json_field "$json" positionMs)
            drops=$(json_field "$json" droppedFrames); drops=${drops:-0}
            if [[ -n "$err" ]]; then
                fail_reason="player error: $err"
                break
            fi
            screen_err=$(json_field "$json" screenError)
            if [[ -n "$screen_err" ]]; then
                fail_reason="screen error: $screen_err"
                break
            fi
            if [[ "$state" == "ended" ]]; then
                echo "content ended at ${elapsed}s -- stopping soak early"
                break
            fi
            if [[ "$state" == "buffering" ]]; then
                rebuffers=$((rebuffers + 1))
            fi
            # A playing stream should cover most of a 10s wall-clock window;
            # under 5s of media progress counts as a hitch.
            if [[ "$(json_field "$json" isPlaying)" == "true" && $((pos - last_pos)) -lt 5000 ]]; then
                stall_streak=$((stall_streak + 1))
                (( stall_streak > max_stall_streak )) && max_stall_streak=$stall_streak
            else
                stall_streak=0
            fi
            new_drops=$((drops - last_drops))
            (( new_drops > 0 )) && total_new_drops=$((total_new_drops + new_drops))
            printf '  t=%4ds pos=%5ds state=%-9s drops+%-3d rebuf=%d\n' \
                "$elapsed" "$((pos / 1000))" "$state" "$new_drops" "$rebuffers"
            last_pos=$pos; last_drops=$drops
            if (( stall_streak >= 3 )); then
                fail_reason="position stalled for ${stall_streak}0s while isPlaying"
                break
            fi
        done
        fatals=$(adbx logcat -d -b crash 2>/dev/null | grep -cE "FATAL EXCEPTION|ANR in" || true)
        summary="played $(( (last_pos - start_pos) / 1000 ))s of media in ${elapsed}s wall, drops=$total_new_drops, rebuffer_samples=$rebuffers, max_stall_streak=$max_stall_streak, fatals=$fatals"
        if [[ -n "$fail_reason" ]]; then
            printf 'FAIL: %s\n%s\n' "$fail_reason" "$summary"
            adbx logcat -d -b crash 2>/dev/null | tail -30
            exit 1
        fi
        if (( fatals > 0 )); then
            printf 'FAIL: fatal exception/ANR in crash buffer\n%s\n' "$summary"
            adbx logcat -d -b crash | tail -30
            exit 1
        fi
        printf 'PASS: %s\n' "$summary"
        ;;
    *)
        die "unknown command: $CMD"
        ;;
esac
