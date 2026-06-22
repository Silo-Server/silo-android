Document version: Media3 1.10.0

# Media3 Documentation — MKV + HDR/DV/Atmos for Silo Android & Android TV

This suite documents how the Silo Android phone (`androidApp/`) and Android TV
(`androidTvApp/`) clients play back MKV files streamed from the Silo server —
specifically the HDR / Dolby Vision video and Dolby Atmos audio paths. Everything is
pinned to AndroidX Media3 **1.10.0** (see `gradle/libs.versions.toml:10`). The
iOS / tvOS clients use a custom `PlayerCore` path (FFmpeg + VideoToolbox + `AVSampleBufferDisplayLayer`) plus AVFoundation routes and are out of scope for this Media3 guide.
The Android app also contains an optional MPV backend path for selected local/device
cases; this document suite remains the Media3 reference.

## Table of contents

- **[01 — Overview and setup](01-overview-and-setup.md)** — What Media3 is, how it
  differs from standalone ExoPlayer 2.x, the 1.10.0 module graph, the core
  `Player` / `ExoPlayer.Builder` / `MediaSession` APIs, the threading model, and the
  Gradle catalog.
- **[02 — MKV container support](02-mkv-container-support.md)** — `MatroskaExtractor`:
  which video / audio / subtitle codecs inside MKV Media3 actually parses, how Dolby
  Vision `dvcC` / `dvvC` configuration boxes are extracted, HDR static metadata
  (`ColorInfo`, `hdrStaticInfo`), subtitle routing, and MKV seek behaviour.
- **[03 — HDR and Dolby Vision](03-hdr-and-dolby-vision.md)** — HDR10 / HDR10+ / HLG /
  Dolby Vision profile matrix, Media3's `ColorInfo`, platform
  `Display.HdrCapabilities`, display / decoder detection, and the tone-mapping
  behaviour on Android 13+.
- **[04 — Atmos and audio codecs](04-atmos-and-audio-codecs.md)** — The codec
  landscape (TrueHD, E-AC-3 JOC, AC-4, DTS:X, AAC / FLAC / Opus), `AudioCapabilities`
  / `AudioCapabilitiesReceiver`, the `AudioFormat.ENCODING_*` constants and API
  levels, `Spatializer` gating, and the "Atmos badge" decision table.
- **[05 — Passthrough, tunneling, offload](05-passthrough-tunneling-offload.md)** —
  How bitstream passthrough through `DefaultAudioSink` works, how Android TV
  tunneling keeps A/V sync when an AVR introduces latency, the audio-offload path
  for battery-efficient music playback, and the decision matrix per Atmos / DTS /
  AAC track.
- **[06 — Android TV and track selection](06-android-tv-and-track-selection.md)** —
  TV-mode detection (`UiModeManager`, `FEATURE_LEANBACK`), display capability probe,
  refresh-rate matching (`Window.setPreferredDisplayModeId`, `Surface.setFrameRate`,
  `setVideoChangeFrameRateStrategy`), the full `TrackSelectionParameters` surface,
  `DefaultRenderersFactory` knobs, and phone vs TV preset examples.
- **[07 — Current implementation review](07-current-implementation-review.md)** —
  Gap analysis of the current Silo code: which Media3 modules are declared per
  module, how `ContinuumPlayerFactory` builds the player today, and every
  concrete gap against the HDR / DV / Atmos target, ranked.
- **[08 — Implementation guide](08-implementation-guide.md)** — The concrete,
  end-to-end "how to land this" recipe: Gradle edits, manifest changes, evolved
  `SiloPlayerFactory`, phone / TV track-selection presets, `MediaSessionService`
  consolidation, per-track preflight, fallback matrix, diagnostics, test matrix, and
  an ordered migration plan.
- **[09 - Direct-play route planning spec](09-direct-play-route-planning-spec.md)** -
  Playback Core v2 spec for Android TV: Media3/MPV first-class route planning,
  direct-play-first fallbacks, server/client contract changes, and validation
  gates for 4K, HDR, Dolby Vision, Atmos, passthrough, and subtitles.

## Quick answers

- **"I just want to play an MKV."** Read **01 §3** (core APIs) and **02** end-to-end.
  Minimum wiring: `media3-exoplayer` + `media3-datasource-okhttp` + MKV-aware
  `DefaultMediaSourceFactory`. For HLS fallback also add `media3-exoplayer-hls`.
- **"Why doesn't Dolby Vision work on my TV?"** Check the profile and selected
  route. DV Profile 5 needs a decoder **and** a DV-capable HDMI link; Profile 7
  is not a launch-safe Android TV DV direct-play claim and needs the fallback
  policy in **09**.
- **"Why is Atmos silent on my AVR?"** Passthrough gate failed. Read **04 §4** on
  `AudioCapabilities.supportsEncoding(...)` and **05 §1** on what has to match for
  TrueHD / E-AC-3 JOC to leave the device as a bitstream. Common causes: eARC not
  negotiated, AVR in stand-by, Android system's "Surround sound" toggle forced off.
- **"Where do I set the buffer size?"** `DefaultLoadControl.Builder` in
  `android-shared/.../player/ContinuumPlayerFactory.kt`, driven by
  `PlaybackBufferPolicy`. The current default is `SmoothPlayback`
  (45 s min / 90 s max, 5 s initial start, 15 s after rebuffer).
- **"The player is out of sync on my TV."** 24 fps source on a 60 Hz panel produces
  3:2 pulldown judder. Turn on refresh-rate matching — **06 §1.3** — and enable
  tunneling — **05 §2**. If the AVR adds fixed latency, tunneling is also what gives
  you frame-accurate sync without estimating the latency in app code.
- **"How do I handle DV Profile 7 sources?"** Do not advertise P7 as Android TV
  Dolby Vision direct play for launch. Use HDR10 base-layer playback, Profile 8.1
  normalization/remux, or transcode with metadata-loss warnings. **09** is the
  current route-planning source of truth.
- **"Where does the `AnalyticsListener` go?"** **08 §8**. Minimum set: decoder init
  name, dropped-frames count, audio underruns. Pair with Media3's built-in
  `EventLogger` under `BuildConfig.DEBUG` for bring-up.

## Conventions

- **Version pin.** Every doc header is dated "Document version: Media3 1.10.0". Do
  not read against a later release without verifying the symbol list — Media3 moves
  `@UnstableApi` promotions every minor release.
- **"(unverified)" marker.** Any claim tagged `(unverified)` in a source doc could
  not be confirmed from a primary source at the time it was written. Treat those as
  hypotheses; re-verify against the Media3 source before relying on them in
  production code.
- **Kotlin + Compose + Koin.** All code samples assume Jetpack Compose (phone) or
  Compose for TV (TV) and Koin dependency injection. The samples use the existing
  shared class names (`SiloPlayerFactory`, `SubtitleManager`,
  `AudioCapabilityManager`, `HdrDisplayController`, etc.).
- **`@OptIn(UnstableApi::class)`.** Many Media3 types remain `@UnstableApi` in
  1.10.0. Opt in at the method or class level, not the module level — the annotation
  is Google's signal that a signature may change between minor versions.
- **Module boundaries.** Player infrastructure lives in `android-shared`. Neither
  the phone `androidApp` nor the TV `androidTvApp` should construct `ExoPlayer`
  directly; both consume the single `MediaSessionService`-owned player through
  the Koin-injected factory/backend layer.

## Sources of truth

Every fact in this suite was cross-referenced against at least one of the following.
When the Android developer-site pages returned only a navigation shell during
research, the claim was sourced from the Media3 source and annotated accordingly in
the originating doc.

- Official Media3 docs: <https://developer.android.com/media/media3>
- Media3 source at tag 1.10.0: <https://github.com/androidx/media/tree/1.10.0>
- Media3 1.10.0 RELEASENOTES (pinned to tag): <https://raw.githubusercontent.com/androidx/media/1.10.0/RELEASENOTES.md>
- Media3 CHANGELOG (moving head, use for cross-version context): <https://github.com/androidx/media/blob/release/CHANGELOG.md>
- Media3 1.10.0 release page: <https://github.com/androidx/media/releases/tag/1.10.0>
- Android platform reference (HDR / audio): <https://developer.android.com/guide/topics/media/hdr-playback>,
  <https://developer.android.com/reference/android/view/Display.HdrCapabilities>,
  <https://developer.android.com/reference/android/media/AudioFormat>,
  <https://developer.android.com/reference/android/media/AudioManager>,
  <https://developer.android.com/reference/android/media/Spatializer>
- AOSP source for cross-checks: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioFormat.java>,
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioManager.java>,
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/MediaCodecInfo.java>,
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/MediaFormat.java>,
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/Spatializer.java>,
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/Display.java>

### Validation log convention

Each document ends with a `## Validation log` section that captures:

- `corrected: <claim> → <what it actually is>` with the source URL that proved
  the correction.
- `verified: <claim> → confirmed by <source URL>`.
- `still unverified: <claim> → <what would verify it>` — typically a device
  test or an OEM-gated API that cannot be asserted from source alone.

If you amend a claim in the body of a doc, mirror the change as a new
`corrected:` entry at the bottom so future readers can audit the diff from the
original research pass without re-running every source fetch.
