---
name: android-playback-testing
description: Use when testing Silo playback on Android devices over ADB — Android TV boxes (the Shield test devices) or Android phones. Covers deep-link playback, the android-playback-test.sh harness and its debug status receiver, soak/stability runs, and verifying 4K/HDR/Dolby Vision output and TrueHD/DTS audio passthrough. Only for this repo's apps and the registered test devices.
---

# Android Playback Testing

Scriptable playback testing for the Silo Android apps (TV and phone) against a dev Silo server.

## Device Registry & Environment

Per-machine details — device serials/capabilities, the dev-server endpoint, DB access, and the known-good fixture table — live in [devices.local.md](devices.local.md) next to this skill (gitignored via the `*.local.md` convention). **Read it first**; if it's missing on this checkout, copy [devices.local.md.sample](devices.local.md.sample) to `devices.local.md` and fill it in with the user's devices (ask for them).

- A registry entry should capture: adb serial, display capability (max resolution, HDR modes from `dumpsys display` `HdrCapabilities`), audio passthrough support (the app logs `AudioCapabilityMgr: Audio output capabilities` on player start), and any network bandwidth ceiling to the server.
- **Pick the least capable device that covers the test** — don't occupy someone's main TV for a generic 1080p check.
- Phones work identically over USB or wireless-debugging serials (the debug receiver and `silo://` scheme are shared with the phone app). Playback UI errors surface the same way.

## Harness

`scripts/android-playback-test.sh` in the silo-android repo. Requires a **debug** build on the device (the receiver no-ops in release). First-time setup (enabling adb on a device, installing a debug build, smoke-testing the hook) is in [SETUP.md](SETUP.md) next to this skill — read it when onboarding a new device or when the status broadcast returns no data. Device selection: `-s SERIAL` or `SILO_DEVICE_SERIAL` env (legacy `SILO_TV_SERIAL` honored). `ADB` env overrides the adb binary.

```
connect <host[:port]>          adb-connect (port defaults to 5555)
play <contentId> [--type T --file-id N --quality Q]
item <contentId>               open detail screen
status                         one-line JSON of live player + screen state
wait-playing [timeoutSec]      poll until isPlaying (fails fast on error/screenError)
pause | resume | stop | seek <seconds> | pos | home
logs [minutes]                 playback logcat (SiloPlayback, Media3Analytics, SiloDovi, SiloDeepLink)
test <contentId> [opts]        home -> play -> wait -> verify position advances
soak <contentId> <seconds> [opts]   long-run health: crash/stall/error detection, drops/rebuffer report
```

Backing hook: `PlaybackDebugReceiver` (android-shared, registered in both apps) answers `am broadcast` with player JSON; `screenError` mirrors the player screen's error banner (terminal server plans never reach the Media3 player — without it, a refusal looks like an idle hang). Guarded by `android.permission.DUMP` + `BuildConfig.DEBUG`.

**Always pass `--quality original` when testing direct play / passthrough / HDR.** Deep links without it use the profile's saved quality, which may take the copy-video HLS route (mp4/AAC stereo, short growing live-window duration).

## Test Recipes

- **Functional check**: `test <contentId> --type movie` on the least capable registered device.
- **Stability**: `soak <contentId> 480 --file-id N --quality original`. Run soaks sequentially per device. 5–10 min covers startup, steady-state, and position pacing.
- **Passthrough ground truth** (passthrough-capable device): NOT logcat — dump the sink:
  `adb shell dumpsys media.audio_flinger` → DIRECT output thread with active track `Format 0E000000` (AUDIO_FORMAT_DOLBY_TRUEHD) = TrueHD bitstream live. Expected transient: the HDMI mode switch kills the first passthrough AudioTrack ~2s in (`AudioTrack write failed: -6`, then a misleading ffmpeg-truehd decoder init from the one-shot PCM retry) — Media3 recovers and passthrough re-establishes. Judge the end state, not the transient.
- **DV/HDR verification** (DV-capable device): decoder `OMX.Nvidia.DOVI.decode` in Media3Analytics logs (Shield); `cast_shell` logcat line `ScreenInfo changed: ... cur mode HDR=1 cur mode DV=1` confirms display mode. DV P7 originals play as `dvhe.08.06` via the server's `client_dv7_to_dv81` plan.
- **Position ground truth**: never trust `dumpsys media_session` (extrapolation snapshots). Use harness `status`/`pos`, or server-side `playback_sessions_sync.position_seconds`.
- **Route decision**: server logs `playback plan decided` with `decision_reason`/`delivery`/`dv_profile` (ops logs, `docker logs` on the dev host).

## Fixtures

Known-good fixture content ids for the dev library (normal-show, high-bitrate 1080p/4K, HDR10/DV P7/P8 + TrueHD, unplayable-codec and corrupt-file repros) are in [devices.local.md](devices.local.md).

**Finding new fixtures** in the server DB: key tables are `media_items` (PK `content_id`) and `media_files` (join on `content_id`; episodes via `episode_id` → `episodes.content_id`). `video_tracks->0->>'video_range_type'` gives SDR/HDR10/DOVI/DOVIWithEL(P7)/DOVIWithHDR10(P8); `->>'dv_profile'`, `->>'pixel_format'` for oddballs. **Dev libraries may contain truncated partial downloads** — filter with an effective-bitrate sanity check before trusting a file as a direct-play fixture: `file_size*8.0/duration/1000000 BETWEEN 30 AND 120` for 4K remuxes.

## Pitfalls

- **Scenario isolation**: `am force-stop org.siloserver.silo` between playback scenarios. A play deep link for content already loaded in a live player resumes the old session instead of restarting; back-press + immediate new link races teardown and drops the link (HOME + ~3s settle if you can't force-stop). `SiloDeepLink` logcat tag traces queued → navigating → arrived.
- Screenshots via `exec-out screencap` can take >60s while the display is in a 4K HDR/DV mode — use generous timeouts.
- `input tap` coordinates are in the logical display space (often 1080p) even when screencap returns 4K — halve coordinates. Leanback apps may ignore touch entirely; drive with keyevents.
- Wholphin comparison client (`com.github.damontecres.wholphin`, connects via jellycompat): plays via libmpv software decode and will "play" unsupported codecs unusably slowly instead of erroring. Its search field traps DPAD focus — send keyevent 61 (TAB) to escape.
- Resolved 2026-07-16: the copy-video HLS route's frozen position / few-second seek bar was the VM clamping position+duration to its own engine-written `state.duration` (downward ratchet). Fixed by clamping to the new `serverDuration` field instead; `status` now also reports `screenPositionSec`/`screenDurationSec` (the ViewModel's seek-bar view, distinct from raw player `durationMs`, which legitimately reads as a short growing window mid-transcode).
