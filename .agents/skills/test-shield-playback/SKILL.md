---
name: test-shield-playback
description: Operate and diagnose Silo Android TV playback on a network-accessible NVIDIA Shield. Use for ADB connection and build installation, silo:// item and play links, title-to-content-ID lookup on a configured Silo dev server, Playback V3 plan inspection, focused logs, screenshots, and verification of Dolby Vision or HDR, audio passthrough, decoder choice, fallbacks, and frame-rate matching. Do not use for phones, production servers, or generic Android UI work.
---

# Test Shield Playback

Use the bundled `scripts/shield-test` helper for repeatable device and dev-server operations. Keep machine addresses and paths in the private config file it loads; never add them to this skill or another committed file.

## Start safely

1. Inspect `git status --short` before building or editing. Preserve unrelated work.
2. Run `scripts/shield-test config-path`, then create that file from `references/config.example.env` if it does not exist.
3. Run `scripts/shield-test doctor`. Fix connectivity or config failures before diagnosing playback.
4. Treat inspection and mutation separately. Logs, display state, AudioFlinger state, server plans, and database searches are read-only. Install, restart, clear-logs, play/open, server deploy, and uninstall change state.
5. Never uninstall or clear app data unless the user explicitly requests losing the Shield's local app state.

The helper always targets `SHIELD_ADB_SERIAL`; do not use an unqualified `adb` command when more than one device may be connected.

## Reproduce a title

Resolve a title through the dev database:

```bash
.agents/skills/test-shield-playback/scripts/shield-test find "title words"
```

The result includes the playable content ID, media type, file count, and copy-ready link. Prefer the returned type instead of guessing:

```bash
.agents/skills/test-shield-playback/scripts/shield-test play <content-id> <movie|episode|audiobook>
```

For a manually composed link, print it with `link`, or open a full app-owned URI with `open`:

```bash
.agents/skills/test-shield-playback/scripts/shield-test link <content-id> movie
.agents/skills/test-shield-playback/scripts/shield-test open 'silo://play/<content-id>?type=movie&fileId=123&quality=original&audioTrackIndex=2'
```

Supported playback query fields are `fileId` (positive integer), `quality`, `audioTrackIndex` (zero-based), and `subtitleTrackIndex` (zero-based). Omit track selectors to use the profile/default selection; do not use `subtitleTrackIndex=-1` in a diagnostic link because the current playback request contract rejects it. The TV link additionally uses `type=audiobook` to choose its audiobook player; movie and episode links use the video player.

When the report is about an HDR format rather than a named title, discover a verified source file first:

```bash
.agents/skills/test-shield-playback/scripts/shield-test find-hdr hlg
```

This returns an exact `fileId` play link so version selection cannot silently choose a different range.

For a clean controlled reproduction:

1. Run `clear-logs` only if erasing the device-wide log buffer is acceptable.
2. Launch the title with `play` or `open`.
3. Wait through the HDMI handshake and until video and audio are stable.
4. Run `snapshot <content-id>`, then `plan 3 <content-id>` and `events 15` if more history is needed.

If a launch reaches an error screen and creates no plan, capture a temporary screenshot before assuming that the latest successful plan describes the reproduction. Restart the app before retrying the same content: arrival-gating can consume a repeated same-content link while the failed player route is still mounted.

Do not infer the result from one layer. Compare the server plan, the formats and decoder selected by Media3, and the Android platform's active display/audio route.

## Helper commands

```text
config-path                     Print the private config path
doctor                          Check config, ADB, package, SSH, database, and repo paths
connect                         Connect to the configured ADB serial
status                          Show device identity, package version, PID, and foreground activity
find <title>                    Find playable dev-server items and print play links
find-hdr <range>                Find exact HLG, HDR10, HDR10+, or Dolby Vision files
link <content-id> [type]        Print a silo://play link without launching it
play <content-id> [type]        Launch a generated play link on the Shield
item <content-id>               Open item detail on the Shield
open <silo-uri>                 Open an advanced item/play URI
logs [line-count]               Print focused Silo/player logs; default 500
clear-logs                      Clear logcat immediately before a controlled reproduction
display                         Print the active display mode, requested mode, and HDR capability line
audio                           Print active AudioFlinger output-thread evidence
plan [count] [content-id]       Print recent Playback V3 decisions for this configured Shield
capabilities                    Print the latest Shield HDR claims and HDR-capable decoders
events [minutes]                Print recent route events for this configured Shield model
snapshot [content-id]           Collect evidence, optionally filtering plans to exact content
restart                         Force-stop and relaunch the TV app
install [apk]                   Replace the installed build; defaults to the arm64 TV debug APK
screenshot <output.png>         Save the current Shield framebuffer
```

Read `references/playback-evidence.md` when diagnosing HDR/Dolby Vision, passthrough, refresh-rate matching, audio sync, or a compatibility fallback.

## Build and deploy only when authorized

Follow the repository's `AGENTS.md` storage rules. Before an Android build, verify `/Volumes/NVMe` is mounted and keep Gradle caches under `DEV_CACHE_ROOT`:

```bash
test -d /Volumes/NVMe
GRADLE_USER_HOME="$DEV_CACHE_ROOT/gradle" ./gradlew :androidTvApp:assembleDebug
.agents/skills/test-shield-playback/scripts/shield-test install
```

Installation preserves app data. Relaunch explicitly with `restart`, or launch the exact test title with its play link.

If server code changed and the user asked to update the dev server, load the private config, inspect that checkout, and deploy from it:

```bash
CONFIG="${SILO_SHIELD_TEST_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/silo/shield-playback.env}"
set -a
source "$CONFIG"
set +a
git -C "$SILO_SERVER_REPO" status --short
cd "$SILO_SERVER_REPO"
make dev-deploy
```

Do not deploy merely to inspect playback. After a deploy, wait for readiness, reproduce with the same link, and collect a new plan and device snapshot.

## Protect private state

- Keep the real config outside the repository and mode `0600`. The checked-in example contains placeholders only.
- Do not print or inspect access tokens, private app databases, shared preferences, cookies, or request authorization headers.
- Do not commit log captures, screenshots, APKs, or machine-specific paths unless the user explicitly requests the artifact and it is safe to share.
- Query only the configured dev database. Never point this workflow at production.
- Report what each evidence layer proves and what remains unverified.
