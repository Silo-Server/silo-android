# ADB Playback Testing

Scriptable playback testing for the Silo Android apps (TV and phone) using
`scripts/android-playback-test.sh` (in the silo-android repo).
One command starts playback of a specific item via deep link; one command
returns live player state as a single JSON line. No logcat scraping, no UI
automation.

## How it works

Debug builds register a broadcast receiver,
`PlaybackDebugReceiver` (`android-shared/.../common/player/debug/PlaybackDebugReceiver.kt`),
in both the phone and TV apps. The harness sends it `am broadcast` intents and
reads the JSON it returns:

- `PLAYBACK_STATUS` → playback state, position/duration, video/audio formats,
  dropped/rendered frame counters, the Media3 player error (if any), and
  `screenError` — the player screen's error banner. `screenError` matters
  because terminal server plans (e.g. "no playable version") never reach the
  Media3 player; without it a refusal is indistinguishable from a hang.
- `PLAYBACK_COMMAND` → transport control (`play`, `pause`, `stop`,
  `seek` + `positionMs`).

Security model: the manifest entry requires the sender to hold
`android.permission.DUMP`, which the adb shell has and third-party apps cannot
get, and the receiver additionally no-ops unless `BuildConfig.DEBUG`. Release
builds expose nothing.

## Setup

1. **Enable adb on the device.**
   - Android TV / Shield: Settings → Device Preferences → About → click
     *Build* 7 times, then Developer options → *Network debugging*. Note the
     IP shown.
   - Phone: enable *USB debugging* (or *Wireless debugging*) in Developer
     options.
2. **Install a debug build** signed into your Silo server:

   ```sh
   ./gradlew :androidTvApp:assembleDebug      # or :androidApp:assembleDebug
   adb connect 192.0.2.10:5555                 # TV over network; skip for USB
   adb -s 192.0.2.10:5555 install -r androidTvApp/build/outputs/apk/debug/androidTvApp-universal-debug.apk
   ```

   Then sign in and pick a profile once by hand (or use your existing signed-in
   test device).
3. **Point the harness at the device.** Either export
   `SILO_DEVICE_SERIAL=192.0.2.10:5555` once, or pass `-s 192.0.2.10:5555` per
   invocation. `ADB=/path/to/adb` overrides the binary if it is not on `PATH`.
4. **Smoke-test the hook:**

   ```sh
   scripts/android-playback-test.sh status
   # {"player":"none"}  ← receiver responding; nothing playing yet
   ```

   If this errors with "no data in broadcast result", the installed build is
   not a debug build (or is stale).
5. **Record your devices and fixtures.** Copy
   [devices.local.md.sample](devices.local.md.sample) to `devices.local.md`
   (same directory, gitignored) and fill in your device registry, server
   endpoint, and known-good fixture ids. Claude reads it when this skill loads,
   so future sessions pick the right device without being told.

## Everyday usage

```sh
export SILO_DEVICE_SERIAL=192.0.2.10:5555

# One-shot functional test: home -> deep link -> wait -> verify position advances
scripts/android-playback-test.sh test movie-tmdb-489064 --type movie

# Force the original file (direct play) instead of the profile's saved quality
scripts/android-playback-test.sh play movie-tmdb-489064 --file-id 223876 --quality original
scripts/android-playback-test.sh wait-playing 45
scripts/android-playback-test.sh status

# Transport
scripts/android-playback-test.sh pause
scripts/android-playback-test.sh seek 300
scripts/android-playback-test.sh resume
scripts/android-playback-test.sh pos          # "302s/6947s ready"
scripts/android-playback-test.sh stop

# 8-minute stability soak: fails on crash (pid change), player/screen error,
# or a 30s position stall; reports drops, rebuffer samples, and fatals
scripts/android-playback-test.sh soak episode-tvdb-275274-1-1 480 --quality original

# Recent playback-relevant logcat (SiloPlayback, Media3Analytics, SiloDovi, SiloDeepLink)
scripts/android-playback-test.sh logs 5
```

Run `scripts/android-playback-test.sh` with no arguments for the full command
list.

## Conventions that keep results trustworthy

- **Pass `--quality original` when testing direct play, passthrough, or
  HDR/DV.** Without it the profile's saved quality applies and the server may
  pick a transcode route (mp4/AAC, short growing HLS-window duration), which
  invalidates format assertions.
- **Isolate scenarios with `adb shell am force-stop org.siloserver.silo`.**
  A play deep link for content already loaded resumes the old session instead
  of restarting it, and a back-press followed immediately by a new link races
  player teardown and silently drops the link.
- **Choose devices by capability.** HDR/DV/passthrough claims are only
  meaningful on a device + display + audio chain that supports them; run
  generic functional tests on your least capable device.
- **Positions from `dumpsys media_session` are snapshots**, extrapolated by
  consumers — they look frozen during steady playback. Use the harness
  `status`/`pos` (real `Player` reads) or the server's client-reported
  position instead.
- **Audio passthrough ground truth is the sink, not logcat.**
  `adb shell dumpsys media.audio_flinger`: a DIRECT output thread whose active
  track shows the compressed format (e.g. `0E000000` =
  `AUDIO_FORMAT_DOLBY_TRUEHD`) means the bitstream is live. A transient
  `AudioTrack write failed: -6` right after playback start is the HDMI mode
  switch killing the first track; Media3 recovers and re-establishes
  passthrough — judge the end state.
