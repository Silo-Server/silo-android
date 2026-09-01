# Media3-only implementation status and Dolby Vision handoff

> Superseded wire-status note (2026-08-06): the original validation recorded
> below predates the platform-neutral v3 contract. Its Media3 and hardware
> evidence remains useful; its server compatibility claims do not establish
> compatibility with the neutral server revision.

Status date: 2026-09-01

This file records proof and remaining gates. It does not redefine the
architecture or migration contract in documents 01 and 02.

## Implemented in Android

- One Media3 `ExoPlayer` is owned by `SiloPlaybackService`; the UI uses a
  `MediaController` and has no engine-switch command.
- MPV source, dependencies, JNI/R8/manifest configuration, preferences,
  controls, and runtime construction are removed. Legacy engine enum values are
  decode-only compatibility values and are never advertised or run.
- Video startup uses protocol v3. A server without `protocol_version=3` and
  `playback_plan_v3` produces the actionable `server_upgrade_required` result.
- Executable plans carry URL, headers, delivery, protocol, timeline, stable
  tracks, recipe, validation claims, subtitle mode, transformations, and
  terminal outcome. An unsupported header-refresh mode fails closed.
- Classified failure, manual track selection, and output capability changes
  use the canonical replan endpoint. A missing session performs a fresh v3
  start at the current position and cannot enter v2 renewal.
- Attempt identity includes plan, output-route generation, and local mutations.
  Repeated keys terminate recovery. Replacement stops the old session; the
  realtime socket rebinds from the changed session-id state key.
- Telemetry is fire-and-forget and emits plan selection/failure, first frame,
  terminal, and stop without blocking recovery.
- MediaCodec and display probes are intersected. Dolby Vision profiles 4/6/7/8
  map to their Android constants; Profile 7 additionally requires concurrent
  DV and HEVC decoder capacity. Profile 8 is not assumed.
- Platform audio remains first and the Media3 1.11.0 FFmpeg extension is
  fallback-only. One sink failure may suppress the exact encoded MIME/layout
  and reprepare through PCM. Audio delay is labelled PCM-only and disabled
  while the validated plan uses passthrough.
- The watchdog separates no-input transport stalls from queued-input decoder
  stalls and has an independent first-frame deadline, so advancing audio cannot
  hide black video.
- Subtitle modes map to mounting behaviour: `off` mounts nothing;
  `render`/`convert` mount a returned artifact; `burn_in` relies on the server's
  video stream and mounts no artifact. Fixture validation remains required.

## Build and automated evidence

- Media3 runtime and FFmpeg extension are aligned at 1.11.0.
- FFmpeg inputs: Media3 `1.11.0`, FFmpeg `n6.0`, NDK `26.3.11579264`;
  decoders `ac3`, `eac3`, `mlp`, `truehd`, `dca`, `alac`; ABIs `armeabi-v7a`,
  `arm64-v8a`, `x86_64`.
- Checked-in AAR SHA-256:
  `bbe661491dd342f2930ffd3bafd6f3e496da3d84586cc5d603432d49daea6b4f`.
  Builds are not byte-identical; pinned inputs and archive contents are the
  reviewable contract.
- Phone and TV debug APKs assemble successfully.
- Focused shared/phone/TV playback tests pass.
- `scripts/check-no-mpv-runtime.sh` passes against source and every generated
  debug APK.
- The full `./gradlew test` run compiles debug and release source sets. Its only
  failures at this checkpoint are eight unrelated TV source-policy tests for
  setup, person detail, shell interaction, typography, and geometry.

## Shield validation completed

Device: NVIDIA Shield `darcy`, Android 11 / API 30, arm64-v8a, reached through
wireless ADB.

- The arm64 TV APK installs and launches as `org.siloserver.silo` version
  `0.2.6` (`versionCode=15`).
- Logcat confirms `AndroidXMedia3/1.10.1` initializes and releases cleanly.
- During app launch, logcat tied the bundled startup-splash playback window to
  `OMX.Nvidia.h264.decode`, which reported a 3840x2160 input at approximately
  60 fps and completed without an application crash. This was not a server v3
  route or a Dolby Vision fixture.
- The attached display exposes only 1920x1080 modes and reports no HDR types.
  The audio route reports speaker/system-audio rather than a proven AVR/eARC
  passthrough sink.

This proves install, startup, Media3 initialization, and hardware H.264 decode
on this connection. It does not prove 4K output, HDR signaling, Dolby Vision
preservation, or lossless audio passthrough.

## Dev-server v3 validation

The dev deployment now implements the canonical v3 start/replan/event flow,
returns executable direct/remux/transcode plans, and records attempt-scoped
route events. End-to-end Shield playback has confirmed original HTTP,
progressive remux, HLS transcode, replanning, and first-frame ingestion.

The live 1080p route matrix and remaining server/client capability gaps are in
[the Shield capability audit](05-shield-1080p-playback-capability-audit.md).
Production release still requires a named minimum server revision and the
Phase 0 fixture gate from document 02. The Android client intentionally retains
no v2 video fallback. Audiobook startup/transcode remains on its existing
audio-scoped contract.

## 4K Dolby Vision TV handoff

Connect the Shield directly or through the intended AVR/eARC chain to a 4K
Dolby Vision display. For each fixture, record:

1. `dumpsys display` showing a 3840x2160 mode and expected DV/HDR types before
   playback.
2. Server delivery, plan id, effective recipe, DV profile, and degradation
   warnings matching the fixture.
3. Media3 decoder and first-frame evidence with no `decoder_no_output` or
   `render_startup_failure` replan.
4. The TV/AVR information panel showing DV for native DV fixtures and HDR10/SDR
   only where the server explicitly adapted the source.
5. For E-AC3 JOC, TrueHD, and DTS-HD: advertised sink capabilities, plan
   passthrough claim, AVR format indicator, and whether PCM retry was used.
6. Text sidecar, embedded text, converted ASS, bitmap, and burn-in subtitles as
   separate fixtures, including seek/transcode timing.
7. One forced network reopen, decoder replan, and output-route change, proving
   bounded attempts, no repeated key, old-session stop, socket rebinding, and
   position continuity.

Do not mark DV or passthrough validated from screenshots alone; retain the
matching plan/telemetry plus device and AVR evidence.
