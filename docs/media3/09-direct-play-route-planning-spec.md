Document version: Playback Core v2 draft, source-audited 2026-06-22

# Android Direct-Play Route Planning Spec

This spec defines the playback architecture Silo Android TV should ship with:
direct play is the default product contract, server remux/transcode is a
measured fallback, and Media3 and MPV are both first-class internal engines.

The spec is intentionally based on the current Android app, the current
`silo-apple` player architecture, Silo server playback APIs, and open-source
Plex/Jellyfin-style clients cloned for local research. It does not require a
ground-up custom Android video engine.

## 1. Product Decision

Silo Android TV should not rewrite the player from scratch around Plezy or any
other single project. It should refactor around a typed `PlaybackExecutionPlan`
that separates:

- server delivery: original file, server remux, server transcode
- client engine: Media3 direct, MPV direct, Media3 progressive/remux, Media3 HLS
- route family: platform-native, compatibility-direct, server-adaptive
- claims: HDR, Dolby Vision, Atmos, audio passthrough, subtitle fidelity
- fallback candidates: alternate direct engine first, server work last

That is Apple-inspired architectural separation, not a field-for-field copy of
`silo-apple`. Android should keep Android-native implementations:

| Apple route | Android equivalent | Decision |
| --- | --- | --- |
| `avPlayerNativeDirect` | `media3Direct` | Use for platform-native assets, future DRM/Cast/external route intents when supplied, and validated HDR/DV/passthrough cases. |
| `playerCoreDirect` | `mpvDirect` | Use as a candidate for validated containers, subtitles, and codecs when the MPV engine envelope satisfies the route's required claims. |
| `avPlayerHLS` | `media3Hls` / `media3ProgressiveRemux` | Use for server adaptive/remux/transcode routes only after direct routes are blocked or fail. |
| `avPlayerLocalDVLoopback` | reserved | Do not build first. Add only if Android device testing proves a local normalization route is required for DV/audio cases MPV and Media3 cannot direct. |

## 2. Launch Contract

Direct play must be first-class from day one:

- Original-file playback is attempted before server remux/transcode whenever at
  least one local engine has a plausible route.
- Target V2 behavior: the server may remux or transcode only when the client plan has explicit
  blockers, or after both direct engines fail with a classified runtime error.
- Server video transcoding is last resort. Prefer original stream, alternate
  direct engine, then audio-only remux, then container remux, then full video
  transcode.
- Subtitle burn-in is last resort and must not be the default for HDR/DV files.
  For HDR/DV, burn-in means video processing and must be shown as a quality
  degradation.
- Feature badges must be claims, not guesses. Do not show "Dolby Vision",
  "Atmos", "TrueHD passthrough", or "PGS supported" unless the selected route
  and current output device can actually preserve that feature.

## 3. Non-Goals

- No external player dependency for the launch contract.
- No VLC integration in the first architecture pass.
- No Android custom FFmpeg/CoreMedia renderer equivalent to Apple `PlayerCore`.
- No Android TV ebooks/Reading exposure.
- No Requests, Admin, or Watch Together surfacing as part of playback work.
- No blanket promise that every Android device can output DV Profile 7 or
  TrueHD Atmos. The route planner must represent those as device-validated
  claims.

## 4. Current Facts

### Android App

The Android code already has the start of the right shape:

- A single `ContinuumPlaybackService` owns the process player and exposes it
  through Media3 `MediaSession`; engine swaps are handled by
  `PlaybackEngineCommand.SET_ENGINE`.
- `VideoPlaybackBackendSelector` can choose Media3 or MPV, but current auto
  policy still defaults to Media3 except hard containers, styled subtitles, and
  a few system routes.
- `ContinuumPlayerFactory` builds Media3 with FFmpeg audio extension priority
  when enabled and can also build an `MpvPlayer`.
- `MpvPlayer` wraps libmpv as a Media3 `BasePlayer` with `gpu-next`, `aaudio`,
  `mediacodec-copy`, bounded cache, and HTTP auth headers.
- Android audio capability probing already advertises HDMI/sink passthrough
  codecs including AC3, EAC3, EAC3-JOC, DTS, DTS-HD, TrueHD, and AC4 when the
  platform reports support.
- Current subtitle sidecar mounting is Media3 text-only. PGS/DVB sidecars are
  detected but filtered out for Media3. ASS/SSA sidecars are included but Media3
  styling fidelity is not equivalent to libass.

Important current gaps:

- The TV mount path sends `SET_ENGINE` and immediately mounts media. The service
  command future is designed to complete after the swap, so the UI should await
  it before mounting.
- `MpvPlayer` currently sets `tls-verify=no`. That may match upstream examples,
  but it is not acceptable as a launch default for authenticated remote playback.
- MPV does not yet expose a route-level passthrough policy such as
  `audio-spdif=ac3,eac3,dts,dts-hd,truehd`.
- MPV is not equivalent to Media3 feature-for-feature today. The current
  capability model marks MPV as supporting subtitle delay, but not audio delay.
- Server start playback returns `play_method` plus `stream_url`; it does not
  return the chosen client engine, route family, fallback ladder, or feature
  claims.
- The shared Android repository method accepts `qualityPreference` and
  `subtitleTrackIndex` parameters, but the current `StartPlaybackRequest` it
  sends does not include them.
- Android cannot currently send `preserve_direct_audio_selection`, so the server
  may promote a direct session to remux just to force a non-default audio track.
  That is hostile to an MPV direct route, where client-side audio selection is
  available.

### Server

The current server model is transport-centric:

- `playback.Resolve` decides `direct`, `remux`, or `transcode` by checking
  video codec, audio codec or passthrough codec, container, and max resolution.
- `PlayRemux` is already used for cheaper audio/container adaptation before full
  video transcode.
- `HandleStartPlayback` accepts flat codec/container/HDR/audio-passthrough
  fields and returns a flat playback session response.
- Server capability planning runs only when the client does not send an
  explicit `play_method` and sends non-empty `codecs_video`. If an explicit
  `play_method` is sent, the handler honors it. If no method is sent and
  capability planning does not run, the handler falls back to direct.
- `HandleStartTranscode` supports copy-video HLS, audio target codec, subtitle
  burn-in, transcode nodes, and remote/local execution. Seeked non-MPEG2
  copy-video HLS requests are currently promoted to H.264 before the 4K guard,
  because arbitrary HEVC seek points can freeze browser-style HLS playback.
- `/stream/{session_id}` serves direct files with HTTP range support and can
  serve remux progressively; full transcode uses HLS manifest/segment endpoints.
  Node/proxy routing can rewrite remux/transcode responses to HLS. Android
  currently maps both `REMUX` and `TRANSCODE` media items to HLS MIME, so the
  V2 plan must carry stream type explicitly instead of deriving it from
  `play_method`.
- The server declares `hdr` and `hdr_details` request fields, but current
  resolver decisions do not use them. HDR/DV routing must be added to the V2
  planner instead of assumed from the existing contract.

The server should remain responsible for authorization, session ownership,
stream URLs, effective-file fallback, transcode-node routing, and future
server-side plan arbitration. The client currently chooses the initial
`file_id`. The server should not guess which Android engine will render a stream
unless the client advertises per-engine capabilities.

### Apple Player

`silo-apple` already implements the architecture Android should mirror at the
contract level:

- `PlaybackExecutionPlan` carries engine, delivery, route family, implementation
  route, stream request, capabilities, route requirements, source metadata,
  validation claims, blockers, decision trace, and degradation warnings.
- Apple separates implementation route from render target. `ActivePlayer` is
  only `.coreMedia` or `.avPlayer`; the exact route is `PlaybackEngineKind`.
- Apple has a central recovery planner and explicit fallback transitions in
  `PlayerViewModel`.
- Apple local DV loopback is a client-local normalization route, not server
  remux/transcode. It is designed to avoid server work on tvOS-specific
  AVPlayer limitations.

Do not copy Apple internals to Android:

- Apple `PlayerCore` uses FFmpeg demux/decode, VideoToolbox,
  `AVSampleBufferDisplayLayer`, and `AVAudioEngine`. Android already has
  Media3 and MPV and should not build a parallel renderer first.
- Apple does not preserve TrueHD Atmos metadata. The Apple target is TrueHD
  decode to PCM, re-encode the bed to FLAC inside local fMP4/HLS, and output
  LPCM/PCM rather than Atmos.
- Apple does not render bitmap subtitles today; bitmap subtitle packets are
  filtered or blocked.
- Apple DV Profile 7 is not raw FEL reconstruction. It is P7-to-8.1-style
  base-layer loopback or HDR10 fallback, not raw P7 HLS, FEL reconstruction, or
  TrueHD Atmos preservation.

### External Client Research

The open-source clients support the same direction:

- Wholphin supports ExoPlayer and MPV. Its README describes ExoPlayer with
  optional extra audio, SSA/ASS, and AV1 software decoding, and MPV for direct
  playing broad formats with strong SSA/ASS support. Under its `PREFER_MPV`
  mode, its playback code can choose ExoPlayer for some HDR/4K software decode
  cases and MPV for broad direct play, and it keeps backend-specific device
  profiles.
- Findroid documents direct play only, with ExoPlayer plus FFmpeg extension and
  MPV. It is useful engine evidence, but weak negotiation evidence: its
  implementation uses a broad "direct play all" style profile and reports direct
  play. Its MPV player uses similar Android libmpv configuration patterns to
  Silo: `gpu-next`, `aaudio`, `mediacodec`, bounded cache, and Media3-style
  track mapping.
- Jellyfin Android TV uses Media3/ExoPlayer, FFmpeg decoder, and libass Media3
  dependencies; it also includes an external-player activity. Its stream
  resolver uses server `PlaybackInfo` flags (`supportsDirectPlay`,
  `supportsDirectStream`, `supportsTranscoding`) instead of URL-shape guessing.
  That argues for plugin/backend boundaries and explicit subtitle strategy, not
  one monolithic player.
- Streamyfin is useful as cautionary evidence: it is MPV-only and posts a broad
  MPV-oriented profile, but its player derives some play-method reporting from
  URL shape. That supports Silo's decision to use server-returned decisions
  instead of URL inference.
- Plezy is useful as a design reference for route concepts: Android ExoPlayer
  default, MPV fallback, libass, passthrough toggles, backend-switched events,
  and explicit audio passthrough properties. It should not be treated as a
  drop-in architecture.

External-client caution: none of these projects prove that MPV, ExoPlayer, VLC,
or Kodi "plays everything" inside Silo. The useful lesson is per-backend
capability profiles, server-returned playback decisions, and recoverable
fallback handoff while preserving position, headers, selected tracks, and
subtitle state.

## 5. Route Taxonomy

### Delivery

`PlaybackDelivery` describes what bytes the server/client will feed to the
engine:

- `originalHttp`: original file bytes over authenticated HTTP/range.
- `serverRemuxHls`: server HLS with copied video and copied or adapted audio.
- `serverRemuxProgressive`: server remux stream, currently used by
  `/stream/{session_id}` for `PlayRemux` in non-proxy paths.
- `serverTranscodeHls`: server HLS with video re-encode.
- `clientLocalNormalization`: reserved future client-local loopback or remux.

### Engine

`PlaybackEngineKind` describes the Android engine:

- `media3Direct`
- `mpvDirect`
- `media3ProgressiveRemux`
- `media3Hls`

Reserved:

- `clientLocalLoopback`
- `externalPlayer`

### Route Family

`PlaybackRouteFamily` describes product behavior:

- `platformNative`: Media3 direct where platform support is preferred or needed.
- `compatibilityDirect`: MPV direct where broad format fidelity is preferred.
- `serverAdaptive`: server remux/transcode, delivered as progressive remux or
  HLS depending on route.
- `clientNormalized`: reserved for future local transformation.

These fields must remain separate. A route can be `direct` delivery but not
platform-native. A server remux is not a client engine. A Media3 player is not a
Dolby Vision claim by itself.

## 6. Playback Plan Model

Android should add a shared model similar to this:

```kotlin
@Serializable
data class PlaybackExecutionPlan(
    val planId: String,
    val protocolVersion: Int = 2,
    val delivery: PlaybackDelivery,
    val engine: PlaybackEngineKind,
    val routeFamily: PlaybackRouteFamily,
    val stream: PlaybackStreamRequest,
    val selectedTracks: SelectedPlaybackTracks,
    val source: PlaybackSourceMetadata,
    val capabilities: RouteCapabilitySnapshot,
    val requirements: RouteRequirements,
    val claims: PlaybackValidationClaims,
    val fallbacks: List<PlaybackFallbackCandidate>,
    val degradationWarnings: List<PlaybackDegradationWarning>,
    val decisionTrace: List<String>,
)
```

The client may compute the final engine locally, but the server response should
carry enough plan data that the UI, telemetry, and fallback code are not
reconstructing decisions from a plain `play_method`.

### Server Response Shape

Keep the current fields for backward compatibility and add `playback_plan`:

```json
{
  "session_id": "01...",
  "media_file_id": 42,
  "play_method": "direct",
  "stream_url": "/stream/01...",
  "audio_track_index": 0,
  "subtitle_urls": [],
  "playback_plan": {
    "protocol_version": 2,
    "plan_id": "01...",
    "delivery": "original_http",
    "engine": "mpv_direct",
    "route_family": "compatibility_direct",
    "selected_tracks": {
      "audio_index": 0,
      "subtitle_index": 3
    },
    "timeline": {
      "player_start_seconds": 0.0,
      "stream_origin_seconds": 0.0,
      "timeline_offset_seconds": 0.0,
      "can_seek_anywhere": true
    },
    "claims": {
      "video": {
        "hdr10": true,
        "dolby_vision": false,
        "dolby_vision_reason": "profile_7_not_validated_on_device"
      },
      "audio": {
        "codec": "truehd",
        "passthrough": false,
        "atmos_preserved": false,
        "reason": "mpv_audio_spdif_not_validated"
      },
      "subtitles": {
        "ass_styling_preserved": true,
        "bitmap_overlay": false
      }
    },
    "fallbacks": [
      {
        "delivery": "original_http",
        "engine": "media3_direct",
        "reason": "alternate_direct_engine"
      },
      {
        "delivery": "server_remux_progressive",
        "engine": "media3_progressive_remux",
        "reason": "audio_or_container_adaptation"
      }
    ],
    "decision_trace": [
      "container=mkv selected compatibility_direct",
      "subtitle=ass selected libass-capable route"
    ]
  }
}
```

`timeline` must be present for server remux/transcode starts and safe to treat
as zero/default for original direct streams. It mirrors the current
`/playback/transcode/start` contract: `player_start_seconds`,
`stream_origin_seconds`, `timeline_offset_seconds`, and `can_seek_anywhere`.
Android currently models the transcode response timeline except
`stream_origin_seconds`; V2 should carry the full set so resumes and copy-video
HLS do not depend on URL or `play_method` inference.

## 7. Capability Envelope V2

The current `ClientCodecCapabilities` is too flat for a dual-engine client.
Keep it for server compatibility, but add a per-engine context:

```kotlin
@Serializable
data class ClientPlaybackContext(
    val protocolVersion: Int = 2,
    val platform: String = "android",
    val formFactor: String,
    val appVersion: String,
    val device: PlaybackDeviceContext,
    val output: PlaybackOutputContext,
    val engines: Map<PlaybackEngineKind, EngineCapabilityEnvelope>,
)
```

Each engine envelope should include:

- availability: enabled, supported on device, build flags, failure reason
- containers: original containers it can attempt directly
- video codecs: codec, profile, level, resolution, bit depth, hardware/software
- HDR: HDR10, HDR10+, HLG, Dolby Vision profiles, and whether each is validated
- audio decode: codecs and max channels the engine can decode locally
- audio passthrough: codecs, max channels, current sink, and active policy
- subtitles: embedded text, sidecar text, ASS/SSA styling, bitmap embedded,
  bitmap sidecar, font attachments
- features: frame-rate matching, tunneling/offload, audio delay, subtitle delay,
  buffer visibility, track switching
- auth: bearer/header support and refresh behavior

The server should use this context for route planning. If V2 context is absent,
the server should fall back to the current V1 resolver.

## 8. Android Route Rules

### Initial Route Selection

When user preference is `Auto`, Android TV should use:

1. Media3 for future/reserved DRM, Cast, and external-display route intents
   when those flags are supplied, and for HLS remux/transcode.
2. Media3 direct for platform-native assets when the required video/audio/subtitle
   claims are validated for the current output path.
3. MPV direct for validated MKV/Matroska, ASS/SSA where styling matters, and
   other containers/subtitles/codecs only when the MPV engine envelope satisfies
   the route's required claims. Do not use MPV as a blanket escape hatch for
   passthrough, Dolby Vision, bitmap sidecars, or every server-remux candidate.
4. Server remux only when no direct engine can satisfy the required route or
   direct failed with a classified non-recoverable local error.
5. Server full transcode only when the video codec, resolution, bitrate, HDR/DV,
   or subtitle burn-in requirement cannot be handled by direct or remux.

This is stricter than the current selector, which defaults to Media3 after a few
MPV checks. The launch selector should become plan-driven rather than
container/styled-subtitle only.

### Runtime Fallback

All playback failures should go through a central recovery planner:

1. Classify failure: unsupported container, unsupported video codec, decoder init,
   audio sink failure, subtitle parser/render failure, HTTP/auth/range failure,
   DRM failure, seek failure, or unknown.
2. If current route is `media3Direct`, try `mpvDirect` when auth and source shape
   allow it.
3. If current route is `mpvDirect`, try `media3Direct` only when the stream is
   platform-native or the failure is MPV-specific.
4. If both direct routes fail, request server remux with video copy when possible.
5. Request full video transcode only after remux is known insufficient or
   disabled by policy.

The recovery planner should update the session's route telemetry. It should not
silently change quality claims.

### Engine Swap Ordering

The UI must await the `SET_ENGINE` result before mounting a media item.
`MediaController.sendCustomCommand` returns a future, and the service completes
its `SessionResult` after `switchEngine`; using that future avoids mounting the
stream into the old engine and then racing the surface rebind.

## 9. Format Policy

### 4K and HDR

- 4K original playback is allowed when either direct engine can decode/render the
  stream and the display mode is acceptable.
- HDR10 and HLG claims require stream metadata, decoder/renderer support for the
  selected engine, and display/output support.
- HDR10+ claims require explicit stream metadata, decoder/display/output support,
  and device validation. Do not claim HDR10+ fallback to HDR10 unless the source
  has a valid HDR10 base path.
- Display probing should use `Display.HdrCapabilities` where available and add
  Android 14+ `Display.Mode.getSupportedHdrTypes()` support before launch
  validation is finalized.
- Current Android HDR disable behavior is track/preference driven; it is not an
  AVPlayer-style guarantee that the app can force SDR output for one HDR-only
  stream.
- Server 4K transcode remains guarded by admin policy. That guard should not
  prevent original 4K direct or copy-video remux.

### Dolby Vision

Dolby Vision must be profile-aware and validation-gated:

- Profile 5 and Profile 8 can claim Dolby Vision preservation on Media3 direct
  only when the stream shape, decoder, and display report support.
- Profile 5 and Profile 8 should remain Media3/platform-owned launch claims
  unless MPV device tests prove metadata survives the MPV/MediaCodec/render
  path.
- Profile 8.1 and 8.4 may have HDR10 or HLG base-layer fallback depending on
  the source profile and output path; the plan must say whether DV is preserved
  or only the base layer is used.
- Profile 10 / AV1 Dolby Vision is not modeled in current Android capability
  payloads and is not a launch claim until probes, server metadata, and device
  tests are added.
- Profile 7 must not be advertised as Android TV Dolby Vision direct play for
  launch. The correct fallback ladder is HDR10 base-layer playback, Profile 8.1
  normalization/remux, or transcode, with FEL/metadata loss called out. Any
  future original-file P7 DV-preservation route must be experimental and backed
  by device-specific validation before the UI exposes a DV claim.
- Old documentation that states "Profile 7 cannot work anywhere on Android" is
  too broad for a future MPV/direct-play architecture, but the opposite claim is
  also unsafe. The launch rule is: do not claim P7 until a device matrix proves
  the exact route.

### Atmos and Passthrough

- Local decode and passthrough are separate capabilities. Media3 plus the
  FFmpeg extension can make codecs playable locally, but decoded PCM output does
  not preserve AVR object metadata.
- E-AC-3 JOC Atmos can be preserved only when the selected engine is configured
  for passthrough and the current sink accepts E-AC-3 JOC.
- E-AC-3 JOC may still be playable as ordinary E-AC-3/DD+ when only E-AC-3 is
  supported, but Atmos must not be claimed unless JOC/object preservation is
  validated.
- TrueHD Atmos can be preserved only when the selected engine is configured for
  TrueHD passthrough and the current HDMI/eARC sink accepts TrueHD. Current
  Android code advertises TrueHD sink support when Android reports it, but MPV
  does not yet configure `audio-spdif`, so MPV passthrough is a spec requirement,
  not a completed fact.
- If passthrough is unavailable, decoding TrueHD to PCM may preserve lossless
  channel audio but does not preserve Atmos objects. UI and telemetry must not
  call that "Atmos preserved".
- Audio-only server remux is preferred over video transcode when video is
  otherwise direct-capable.
- Future passthrough implementation must disable passthrough or block playback
  speed changes, because compressed bitstream passthrough cannot be time-scaled
  safely. Current Android does not implement this guard.
- DTS launch claims cover DTS core and the current Silo `dts_hd` bucket only.
  Current code does not distinguish DTS-HD HRA, DTS-HD MA, DTS:X, or DTS-UHD;
  Android 14+ probing and validation are required before exposing those as
  preserved formats.
- Until MPV passthrough is configured and validated, Media3 owns platform
  passthrough decisions.

### ASS/SSA Subtitles

- ASS/SSA styling is first-class. MPV direct should be the preferred route for
  ASS/SSA unless Media3/libass integration is added and validated.
- Media3 SSA/ASS support is acceptable for basic text rendering, but it should
  not be treated as full libass fidelity.
- Server burn-in for ASS/SSA is a fallback only. It should not be the normal
  path for direct-play launch.
- Font attachments and external font bundles should be part of the plan; the
  selected route should say whether fonts are preserved.

### Bitmap Subtitles

- PGS, DVDSub, and DVB subtitles must be represented explicitly in route
  requirements.
- Embedded bitmap subtitles may be playable in some Media3 routes, but style
  controls do not apply and sizing/render behavior must be validated before any
  launch claim.
- Embedded bitmap subtitles should prefer MPV direct only when validated.
- Sidecar `.sup` and other bitmap sidecars are not currently mounted into Media3
  because `SubtitleManager` filters Media3 sidecars to text formats. MPV has
  sidecar loading plumbing, but bitmap sidecar route selection, eager/selected
  mount behavior, and `.sup` validation are not complete. Do not claim sidecar
  PGS/VobSub direct support for launch until those pieces are wired and tested.
- Server burn-in is last resort and should require an explicit degradation
  warning, especially for HDR/DV.

## 10. Implementation Plan

### Phase 0 - Contract and Safety

- Add shared `PlaybackExecutionPlan` models without changing playback behavior.
- Add `ClientPlaybackContext` V2 while continuing to send V1 flat fields.
- Add server response `playback_plan` while preserving current session fields.
- Add `stream_origin_seconds` to Android transcode/timeline models and include
  timeline semantics in V2 plans.
- Fix MPV TLS verification policy for production remote playback.
- Make TV await `SET_ENGINE` before `backend.mount(mediaSpec)`.
- Add Android wire support for `preserve_direct_audio_selection`,
  `qualityPreference`, and selected subtitle track.
- Normalize Android/server `max_resolution` vocabulary so values such as
  `1440p` or `sd` do not accidentally rank as unsupported and trigger
  unnecessary transcode.

### Phase 1 - Plan-Driven Client Selection

- Replace TV-local engine heuristics with a planner that consumes the response
  plan and local current output state.
- Expand `VideoPlaybackBackendSelector` to consider route requirements:
  container, subtitle shape, bitmap sidecar, HDR/DV claim, passthrough claim,
  future DRM/Cast/external-display intents, and user override.
- Add route telemetry: selected engine, delivery, claims, blockers, fallback
  reason, decoder names, dropped frames, audio underruns, subtitle renderer.

### Phase 2 - Server Planner

- Extend the server resolver from `PlayDecision` to `PlaybackPlanDecision`.
- Use per-engine client context to avoid remuxing an MKV just because Media3
  does not like the container when MPV can direct-play it.
- Treat server-returned method/stream decisions as authoritative. Do not infer
  DirectPlay, DirectStream, or Transcode from URL extension or URL shape.
- Keep audio-only remux and copy-video HLS as cheaper fallbacks.
- Preserve selected audio/subtitle indexes in the start request and response.
- Include effective source metadata in the start response: effective file id,
  container, selected audio track details, subtitle shape, video codec/profile,
  resolution, bitrate, HDR format, DV profile, bit depth, and dimensions.
- Add component decisions for container, video, audio, and subtitles instead of
  only a top-level play method.
- Add a dry-run planning endpoint or non-counting decision mode if the client
  must choose an engine before creating a counted playback session.
- Return fallback candidates in priority order.

### Phase 3 - MPV First-Class Route

- Add MPV sidecar subtitle loading.
- Add MPV audio passthrough controls and sink-gated `audio-spdif` policy.
- Add MPV track mapping and UI feature parity tests for audio/subtitle changes.
- Add MPV error classification and fallback handoff to the recovery planner.
- Validate MPV HDR/DV output on target devices before enabling premium claims.

### Phase 4 - Validation Matrix

Create repeatable validation cases and store results under
`docs/media3/validations/`:

- 4K HDR10 HEVC MKV direct on Media3 and MPV.
- Dolby Vision P5/P8 MP4 on Media3 with a DV-capable TV.
- Dolby Vision P5/P8 MKV on Media3/MPV as device-validation cases, not assumed
  platform-native support.
- Dolby Vision P7 UHD remux: original-file route planning, HDR10 base-layer
  fallback, normalized Profile 8.1 fallback, or transcode, with no P7 DV badge
  unless future device evidence proves the exact route.
- E-AC-3 JOC Atmos passthrough to AVR/soundbar.
- TrueHD Atmos passthrough to eARC AVR/soundbar.
- TrueHD decode without passthrough, verifying UI does not claim Atmos.
- DTS and DTS-HD passthrough/remux fallback.
- ASS/SSA with positioning, fonts, karaoke, and complex styles.
- PGS forced subtitle in MKV and `.sup` sidecar.
- Mid-stream audio/subtitle switch on direct and remux routes.
- Seek/resume after direct, remux copy-video HLS, and full transcode.
- Token expiry/refresh and remote server TLS validation.

Target devices should include at least one NVIDIA Shield-class Android TV, one
Chromecast/Google TV-class device, and one Fire TV-class device before launch
claims are finalized.

## 11. Acceptance Criteria

The Android TV app is launch-ready for this playback contract when:

- A playback response can explain why the selected route is direct, remux, or
  transcode and why the selected client engine is Media3 or MPV.
- The app tries an alternate direct engine before server remux/transcode for
  recoverable local direct failures.
- Server remux/transcode requests are traceable to explicit blockers.
- The UI can display route diagnostics internally without reverse-engineering
  from `play_method`.
- ASS/SSA is handled by a libass-capable route by default when route constraints
  allow MPV or another validated libass path; Media3 SSA/ASS text support is not
  full libass fidelity.
- Audio passthrough claims are tied to current sink capability and engine config.
- DV and Atmos badges are validation-backed per route.
- The validation matrix contains pass/fail evidence for every premium claim the
  launch UI exposes.

## 12. Evidence Table

| Claim | Evidence |
| --- | --- |
| Android has a single MediaSession-owned player and async engine swap result. | `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/ContinuumPlaybackService.kt:48`, `:220` |
| Android current auto selector is Media3-first except MPV for hard containers/styled subtitles. | `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelector.kt:5` |
| Android MPV route uses libmpv with `gpu-next`, `aaudio`, `mediacodec-copy`, bounded cache, and currently disables TLS verification. | `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/mpv/MpvPlayer.kt:49`, `:180`, `:201` |
| Android marks MPV audio delay unsupported today. | `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/backend/VideoBackendCapabilities.kt:35` |
| Android Media3 factory prefers FFmpeg audio extension when enabled and maps remux/transcode to HLS. | `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/ContinuumPlayerFactory.kt:73`, `:88`, `:336` |
| Android subtitle sidecars are text-only for Media3 and bitmap subtitles are only detected. | `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/SubtitleManager.kt:40`, `:258`, `:371` |
| Android advertises sink passthrough codecs from Media3/Android audio capabilities. | `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/AudioCapabilityManager.kt:94` |
| Android shared API response is flat session/url/play_method, not a plan. | `shared/src/commonMain/kotlin/com/continuum/app/model/playback/PlaybackModels.kt:13` |
| Android repository currently drops quality preference and subtitle track index from the start request. | `shared/src/commonMain/kotlin/com/continuum/app/repository/PlaybackRepository.kt:20` |
| Server resolver currently decides direct/remux/transcode from video/audio/container/resolution. | `../../../silo-server/internal/playback/resolver.go:57` |
| Server start playback request/response are flat and transport-centric. | `../../../silo-server/internal/api/handlers/playback.go:256`, `:280` |
| Server planning only runs for capability-led starts with no explicit play method. | `../../../silo-server/internal/api/handlers/playback.go:1225` |
| Server already supports audio/container remux before full transcode and copy-video HLS. | `../../../silo-server/internal/playback/resolver.go:97`, `../../../silo-server/internal/api/handlers/playback.go:2007` |
| Server `/stream/{session_id}` serves direct/remux progressively, while transcode uses HLS manifest/segments. | `../../../silo-server/internal/api/handlers/stream.go:60`, `../../../silo-server/internal/api/handlers/playback.go:440` |
| Server transcode start response carries timeline semantics for seek/copy-video playback. | `../../../silo-server/internal/api/handlers/playback.go:343` |
| Apple has a typed playback execution plan with route, engine, capabilities, claims, blockers, trace, degradation warnings, and recovery-plan inputs. | `../../../silo-apple/iosApp/iosApp/Screens/Player/PlaybackExecutionPlan.swift:358` |
| Apple separates route family and implementation route. | `../../../silo-apple/iosApp/iosApp/Screens/Player/PlaybackExecutionPlan.swift:159`, `:174` |
| Apple centralizes route fallback/recovery. | `../../../silo-apple/iosApp/iosApp/Screens/Player/PlaybackRecoveryPlanner.swift:3` |
| Apple TrueHD path is not TrueHD Atmos passthrough. | `../../../silo-apple/docs/tvos-player/07-profile7-dv-truehd-loopback-spec.md:97`, `:237` |
| Jellyfin Android TV ExoPlayer backend uses Media3, FFmpeg extension, optional libass, buffer config, and offload prefs. | `../../../android-tv-client-research/jellyfin-androidtv/playback/media3/exoplayer/src/main/kotlin/ExoPlayerBackend.kt:75` |
| Jellyfin Android TV stream resolver trusts server PlaybackInfo flags for DirectPlay/DirectStream/Transcode. | `../../../android-tv-client-research/jellyfin-androidtv/playback/jellyfin/src/main/kotlin/mediastream/JellyfinMediaStreamResolver.kt:31` |
| Wholphin supports ExoPlayer and MPV and uses MPV for broad direct play/ASS. | `../../../android-tv-client-research/wholphin/README.md:7`, `:55` |
| Wholphin constructs separate ExoPlayer and MPV players with backend-specific renderer/subtitle choices. | `../../../android-tv-client-research/wholphin/app/src/main/java/com/github/damontecres/wholphin/services/PlayerFactory.kt:62` |
| Findroid is engine evidence for ExoPlayer/MPV direct-play focus, but weak negotiation evidence because it uses broad direct-play reporting. | `../../../android-tv-client-research/findroid/README.md:27`, `../../../android-tv-client-research/findroid/data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryImpl.kt:306` |
| Streamyfin supports the warning against URL-shape play-method inference. | `../../../android-tv-client-research/streamyfin/utils/atoms/settings.ts:173`, `../../../android-tv-client-research/streamyfin/app/(auth)/player/direct-player.tsx:934` |
| Plezy uses Android ExoPlayer with MPV fallback concepts, passthrough toggles, and backend-switched events. | `../../../android-tv-client-research/plezy/lib/mpv/player/player.dart:333`, `../../../android-tv-client-research/plezy/lib/mpv/player/platform/player_android.dart:65`, `:295` |
| Plezy MPV fallback preserves position, headers, properties, observers, and external subtitle state during handoff. | `../../../android-tv-client-research/plezy/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerPlugin.kt:800` |

## 13. Validation Log

- verified: Android already has both Media3 and MPV internal player paths.
  Confirmed by `ContinuumPlayerFactory` and `MpvPlayer`.
- verified: current Android routing is not yet a full execution plan. Confirmed
  by flat `PlaybackSessionResponse` and `VideoPlaybackBackendSelector`.
- verified: server resolver can distinguish direct/remux/transcode, but not
  per-engine Android routes. Confirmed by `playback.Resolve` and
  `playbackSessionResponse`.
- verified: server remux is not always HLS; non-proxy remux can be progressive
  through `/stream/{session_id}`, while full transcode is HLS.
- verified: Apple provides the strongest local precedent for a typed route plan.
  Confirmed by `PlaybackExecutionPlan`, `ApplePlaybackRoutePlanner`, and
  `PlaybackRecoveryPlanner`.
- corrected: "Rewrite Android player from the ground up based on Plezy" should be
  "Refactor Android around a plan-driven Media3/MPV engine boundary; use Plezy
  as supporting evidence only."
- corrected: "MPV plays everything" is not an acceptable product or technical
  claim. The launch-safe claim is per-backend, per-device, per-output route
  validation with server-returned decisions.
- corrected: "Android can direct play DV Profile 7" is not a launch-safe claim.
  Day-one P7 handling must be HDR10 base fallback, Profile 8.1 normalization or
  remux, or transcode, with metadata-loss warnings.
- corrected: Dolby Vision Profile 10 is not currently modeled in Android
  capability payloads and must not be exposed as a launch claim.
- corrected: "TrueHD decoded to PCM means Atmos preserved" is false. Atmos
  preservation requires passthrough or an object-preserving route.
- corrected: "DTS-HD" does not automatically mean DTS-HD MA, DTS:X, or DTS-UHD
  preservation. Those require explicit probing and validation.
- still unverified: MPV direct preserving Dolby Vision metadata on target Android
  TV devices. Requires device matrix.
- still unverified: MPV TrueHD/DTS-HD passthrough in Silo Android. Requires
  `audio-spdif` implementation plus HDMI/eARC device tests.
- still unverified: PGS sidecar support in Silo Android MPV route. Requires
  explicit sidecar loading and tests.
