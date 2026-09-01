# Media3-Only Player Architecture

> Neutral-v3 note (2026-08-06): this document remains authoritative for the
> Android Media3 runtime, but its wire examples predate the platform-neutral
> contract. The server repository's `docs/architecture/playback-protocol-v3.md`
> owns wire semantics. Android now receives neutral delivery capabilities,
> stores server-minted opaque plan-attempt keys, and uses opaque
> `output_context_id` values.

Status: **implemented in Android and validated against the dev-server v3 flow;
the published minimum server revision and named hardware validation remain
gated**.

This document is normative for finished playback behaviour. Migration order,
temporary compatibility code, release gates, and file-removal checklists belong
in the [migration specification](02-migration-compatibility-validation.md).

## 1. Decision and terminology

Silo Android will use Media3 ExoPlayer as its only in-process video engine. It
will not construct, select, or advertise MPV. Compatibility comes from choosing
different server deliveries, not swapping local players.

The following terms are distinct throughout these specifications:

- **Engine**: the sole in-process Media3 ExoPlayer instance or replacement
  instance owned by `SiloPlaybackService`.
- **Delivery**: original HTTP, server remux, or server transcode bytes supplied
  to Media3.
- **Output route**: the current display and audio-sink path, identified by an
  `output_route_generation` that changes when HDMI/eARC/Bluetooth/USB state
  changes.
- **Playback plan**: the server's executable decision for one delivery.
- **Playback attempt**: the complete user start action across any replans,
  identified before `/start` by `playback_attempt_id`.
- **Plan attempt**: one execution of a plan, identified by `plan_attempt_id` and
  an idempotent, opaque `plan_attempt_key` minted by the server. Android stores
  and echoes that key unchanged; it never derives or interprets one locally.
- **Replan**: a new server decision after a classified failure, capability
  change, track change, or quality change.

“Direct play everything” therefore means direct play every source combination
validated for the selected Android decoder and current output route. It does not
mean sending unsupported bytes to Media3 and hoping playback succeeds.

## 2. Target runtime

```text
Compose phone / Compose TV UI
             │ MediaController
             ▼
SiloPlaybackService + MediaSession
             │ owns one
             ▼
Media3 ExoPlayer
 ├── MediaCodec video and audio renderers
 ├── DefaultAudioSink + optional FFmpeg audio decoder
 ├── progressive and HLS media sources
 ├── subtitles, track selection, and analytics
 └── existing PiP, audio focus, and session integration
             │
             ▼
Versioned Silo playback API
 ├── original HTTP
 ├── progressive/HLS remux
 ├── video-copy + audio adaptation
 └── HLS video transcode
```

The service has no engine-selection command, background MPV initialization,
cross-engine state transfer, or alternate-player recovery branch. Re-preparing
or rebuilding ExoPlayer for a bounded same-engine recovery is allowed when the
position, selected tracks, playback intent, and server session are preserved.

## 3. Authoritative client/server contract

The server owns authorization, source truth, stream/session creation, capacity,
and the effective output recipe. Android owns current device/output capability
reporting, local Media3 execution, failure classification, and presentation.

A compatible server release is a prerequisite to the Android cutover. Phase 0
records the current gaps and required proof in the
[migration specification](02-migration-compatibility-validation.md#2-phase-0-server-readiness).

### 3.1 Decision request

Android sends one versioned request containing:

- selected file and stable selected audio/subtitle track identities;
- client-generated `playback_attempt_id`;
- symbolic user quality preference;
- user `subtitle_fidelity_preference`;
- current video, display, audio, and subtitle capabilities;
- `output_route_generation`, metering state, bandwidth estimate, and any
  user bandwidth cap used by `auto`;
- app, SDK, ABI, form-factor, and protocol feature versions;
- start position and any active playback-session identity;
- for recovery, `replan_request_id`, failed `plan_id`, `plan_attempt_id`,
  `plan_attempt_key`, classified failure, attempt count, and
  `attempted_plan_keys`.

Android must not force `play_method=direct`. The server revalidates every direct
delivery against the supplied capabilities and source metadata.

### 3.2 Executable response

A playable response contains everything Android needs without inventing codec,
bitrate, HDR, or subtitle recipes:

- protocol version, `plan_id`, expiry, and server playback-session identity;
- delivery, stream URL, stream protocol, container/MIME type, required request
  headers, and token/header refresh semantics;
- source start, stream origin, player start, timeline offset, seek window, and
  seek-restoration semantics;
- effective video/audio codecs, resolution, frame rate, bitrate, and dynamic
  range;
- selected track identities and subtitle mode; a converted subtitle includes
  its artifact URL, MIME/format, and timing origin;
- named media transformations and their validated claims;
- degradation warnings and a stable decision reason.

Runtime failure normally causes a replan request because server capacity,
position, output route, or track selection may have changed. If ordered fallback
candidates are retained for compatibility, every candidate must be independently
executable with the same fields, plus defined session replacement, expiry, and
seek restoration semantics.

The other valid response is terminal `adaptation_unavailable`, with a reason
such as `transcoding_disabled`, `capacity_unavailable`, `conversion_unsupported`,
or `policy_denied`. Android shows an actionable error and does not retry the same
source/delivery/output-route combination.

### 3.3 Playable deliveries

| Delivery | Result | Allowed use |
| --- | --- | --- |
| `original_http` | Original authenticated bytes with range support | Container, selected video/audio, dynamic range, output route, and quality policy are valid. |
| `server_remux_progressive` | Media3-compatible progressive stream | Container normalization is required; compatible video remains copied. |
| `server_remux_hls` | Segmented stream with copied video and copied or adapted audio | HLS delivery is required without video re-encoding. |
| `server_transcode_hls` | Server-selected video/audio rendition | Video/profile/range is unsupported, subtitle burn-in is required, or the user/server quality policy requires video conversion. |

Remux is not a mandatory intermediate step. The server chooses the least
destructive valid result directly. An audio-only incompatibility copies video
and adapts audio; it does not trigger video transcoding.

Subtitle artifact delivery is independent of video delivery. For example,
`original_http` may include a server-converted sidecar subtitle without remuxing
otherwise compatible video/audio.

### 3.4 Direct eligibility

Original HTTP is allowed only when all of these pass:

1. The extractor/container path is validated for the source.
2. The selected video codec, profile, level, bit depth, dimensions, frame rate,
   bitrate, and dynamic range fit the decoder and display output route.
3. The selected audio can decode locally or pass through with its actual channel
   layout on the active output route.
4. The selected subtitle can render with the required fidelity or the plan
   supplies an independently converted renderable artifact. Subtitle burn-in
   disqualifies original video delivery.
5. The user did not request a quality conversion.

Eligibility is preflight evidence, not proof of correct output. Runtime recovery
in section 8 remains required.

### 3.5 Quality policy

Wire values are `auto`, `original`, `2160p`, `1080p`, `720p`, and `480p`; the UI
may label `2160p` as “4K.” Their meanings are:

- `auto`: server-selected from the request's bandwidth/metering inputs and user
  cap, plus server/administrator constraints;
- `original`: no user-requested quality reduction, while compatibility
  adaptation remains allowed;
- a resolution rung: maximum output resolution with no upscaling.

The server returns the effective codec, range, resolution, bitrate, and reason.
Initial HLS transcodes use one effective rendition; true multi-variant ABR is a
separate protocol feature and must not be implied by the word “adaptive.”

## 4. Capability protocol

Protocol v3 adds top-level client features, including `media3_only`, and omits
MPV from the outgoing engine map. Legacy MPV enum tolerance is migration-only;
it is not part of the target capability model.

Capabilities are reported for the current output route, not as a union of every
packaged decoder:

### Video

- codec, profile, level, bit depth, and relevant chroma constraints;
- maximum width, height, frame rate, and bitrate per codec/profile;
- hardware or software decoding status;
- HDR10, HDR10+, HLG, and native Dolby Vision support intersected with current
  display capability.

### Dolby Vision source metadata

The server supplies, where known:

- DV profile and level;
- base-layer presence, codec, and `dv_bl_compat_id`/range compatibility;
- Profile 8 variant/source range;
- Profile 7 MEL/FEL classification derived from server media analysis.

Android decoder enumeration alone cannot infer a file's MEL/FEL status. Native
DV decoding and compatible-base-layer playback are separate claims.

### Audio

- local decode support per encoding;
- passthrough support per encoding and channel/layout on the active sink;
- current sink type and `output_route_generation`, so an output-route change
  invalidates the plan.

### Subtitles

- embedded and sidecar support separately;
- parser/render support by format;
- ASS styling and font-attachment fidelity;
- bitmap rendering, conversion, and burn-in availability.

Unknown or unvalidated capability combinations are conservative blockers, not
optimistic direct-play claims.

## 5. HDR and Dolby Vision

Media3 extractors parse supported containers and HDR metadata. Android platform
codecs and the device/display path determine decoding, composition, and output.
[Supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats#hdr_video_playback)

Routing rules:

1. Native HDR/DV direct play requires compatible source metadata, decoder, and
   active display output route.
2. Profile 5, Profile 7 MEL, Profile 7 FEL, and relevant Profile 8 variants are
   evaluated separately.
3. An unchanged compatible base layer is usable only when source metadata and
   fixtures validate that exact base-layer range/output path.
4. Metadata stripping, RPU removal, or color conversion is a distinct server
   transformation and requires a named validated claim.
5. Unsupported DV/HDR requires a validated server HDR10 or SDR result; Phase 0
   defines the implementation gate.
6. Client-side tone mapping, metadata stripping, or RPU conversion is not a
   substitute for a server-declared plan.

Diagnostics record source and planned/effective range. Claims about the actual
HDMI/display mode require hardware evidence such as the TV mode indicator; a
Media3 callback cannot establish that externally visible result.

## 6. Audio and passthrough

For the selected track and current output route, the outcomes are:

```text
compressed encoding + layout supported by sink → passthrough
local MediaCodec or FFmpeg decoder supported   → decoded PCM
neither                                         → video-copy server audio adaptation
no permitted server adaptation                  → adaptation_unavailable
```

The platform renderer gets first opportunity; the FFmpeg extension remains a
decoder fallback. Media3 1.11.0 implements this ordering through
`EXTENSION_RENDERER_MODE_ON`; `PREFER` would place extension renderers first.
[Pinned AndroidX source](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java)

When encoded output fails, Android may perform one bounded local recovery in the
same Media3 engine: suppress the failed encoding/layout, re-prepare or rebuild
ExoPlayer at the same state, and force decoded PCM where available. This
requires an explicit mutable sink/renderer policy; changing an unrelated flag
does not count as a retry. A second failure requests a server replan.

Audio delay is available only for decoded PCM. It cannot alter a compressed
passthrough bitstream; the UI must not imply otherwise while passthrough is
active.

## 7. Subtitle policy

The plan uses one explicit mode per selected track: `render`, `convert`,
`burn_in`, or `none`. `none` means the user selected no subtitle; it is not a
fallback for an unsupported selected track. Such a track must resolve to
`convert`, `burn_in`, or `adaptation_unavailable`.

The request carries the user's `subtitle_fidelity_preference`. The server
combines it with administrator policy and returns the effective
`subtitle_fidelity_policy`: either `allow_simplified_rendering` or
`require_authored_fidelity`.

| Source path | Target behaviour |
| --- | --- |
| Supported embedded/sidecar text | Render with Media3. |
| ASS/SSA without required full styling | Render only when `allow_simplified_rendering` applies; otherwise convert or burn in with a warning. |
| PGS, VobSub, or DVB | Test embedded and sidecar paths separately. Media3 1.11.0 includes parsers, but Silo wiring and fidelity still require fixtures. [Pinned parser source](https://github.com/androidx/media/blob/1.11.0/libraries/extractor/src/main/java/androidx/media3/extractor/text/DefaultSubtitleParserFactory.java#L55-L64) |
| Font-attached styled subtitle | Render only when attachment/style fidelity is validated; otherwise convert or burn in. |
| Concurrent secondary subtitle | Unsupported until a separate implementation and fixture set exist. |

Burn-in forces video processing and can change HDR/DV output. The plan must
declare that degradation rather than treating it as a direct-play detail.

## 8. Runtime recovery

The runtime uses one centralized startup/watchdog state machine.

| Failure class | Required evidence | Action |
| --- | --- | --- |
| Network/startup stall | The bounded no-progress deadline expired and the decoder received no input, or network/demux progress stopped | Permit one bounded reopen/resume of the same plan attempt, tracked by `transport_reopen_used`; do not label it a decoder failure. |
| Decoder no-output | Decoder input was queued while rendered + skipped + dropped output remains zero for the bounded window | Replan with decoder/profile/range failure. |
| Render-startup failure | Input was queued and only skipped/dropped output occurred; no frame rendered by the overall first-render deadline | Replan with the counter-derived subtype. |
| Rendered first frame | Media3 reports a frame rendered to its output | End the no-output watchdog; do not claim visible or HDR-correct display output. [Callback contract](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener#onRenderedFirstFrame(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,java.lang.Object,long)) |
| Encoded-audio failure | Classified `AudioTrack`/sink failure | One local PCM retry, then replan. |
| Subtitle or other format failure | Stable classified error and selected track | Replan with the exact blocker. |

Every recovery carries `replan_request_id`, `plan_id`, `plan_attempt_id`,
`plan_attempt_key`, `output_route_generation`, selected tracks, playback
position, failure class, and `attempted_plan_keys`. Except for the single
transport reopen above, the client does not repeat a key after the same
classified failure. A local PCM retry creates a new key containing the
suppressed encoding/layout and is permitted once. A terminal server response
ends the loop.

## 9. Observability

Playback telemetry is a versioned server-ingested contract, not logcat-only
diagnostics. Each event includes:

- protocol, `playback_attempt_id`, `plan_id`, playback-session,
  `output_route_generation`,
  `replan_request_id`, `plan_attempt_id`, and `plan_attempt_key`;
- requested and effective quality;
- delivery, stream protocol, container/MIME, effective codecs, resolution, and
  planned range;
- selected decoder and hardware/software status;
- current display/audio sink snapshot and audio outcome;
- selected subtitle identity and mode;
- decision reason, degradation warnings, failure class, and retry outcome;
- decision, prepare, decoder-input, first-decoder-output where observable,
  rendered-first-frame, and terminal timing;
- SDK, ABI, form factor, and app version for defined rollout cohorts.

The server defines authentication, retention, sampling, privacy, aggregation,
metric denominators, and minimum cohort sizes before telemetry can gate a
release.

## 10. Non-goals

- No second in-process video engine.
- No client-side video transcoder, tone mapper, DV RPU converter, or custom
  FFmpeg video renderer.
- No external player as the compatibility fallback.
- No guarantee that every legacy source direct-plays.
