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
- `output_route_generation` and any user bandwidth cap (`bandwidth_cap_kbps`).
  A start may carry the bandwidth probe's estimate and a lowering replan its
  step-down estimate (see 3.6). It always sends `metered: false`: the server
  ignores it, and older servers require it on `/api/v2` but drop `auto` to
  their metered limit on `true`;
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

Wire values are `auto`, `2160p`, `1080p`, `720p`, and `480p`, plus the plan's
own rung labels picked from the player's Quality menu; the UI may label `2160p`
as “4K.” Their meanings are:

- `auto`: play the source whenever the device and the measured connection can
  carry it, converting only when its capabilities, the bandwidth estimate, a
  learned limit, the user's bandwidth cap, or a server/administrator limit
  require it;
- a resolution rung: maximum output resolution with no upscaling. It ignores
  the bandwidth estimate and any learned limit; only the cap still applies.

There is no Original choice. A stored or linked `original` (also `source` and
`max`) reads as `auto`, and video requests never send it, because older servers
take it to mean never convert. The player's Quality menu hides the source entry
(`original`, `preserves_source`) that older servers still list; Auto is the way
back to the source. The audiobook player still sends `original`.

The server returns the effective codec, range, resolution, bitrate, and reason.
Initial HLS transcodes use one effective rendition; true multi-variant ABR is a
separate protocol feature and must not be implied by the word “adaptive.”

Repeated rebuffering lowers quality, as the server repo's
`docs/design/2026-10-17-buffering-quality.md` specifies. The phone and TV
players run one `BufferingQualityController` (`shared/`), fed by one Media3
adapter, `BufferingQualityPlayerAdapter` (`android-shared/`). It has the web
player's stall detector and the spec's state machine, and
`BufferingQualityVectorsTest` replays the spec's vendored `vectors.json`. Its
mode is `playback.buffering_quality_mode` (Settings → Lower quality when
buffering): `always`, the default, lowers Auto at once and offers **Restore
quality**; `ask` asks first; `never` only detects and reports. A quality the
viewer picked is always asked about. Lowering Auto is a `quality_change` replan
with the step-down estimate (see 3.6); lowering a picked rung names the next
rung's label and carries no estimate. In a Watch Party the controller is
suspended: it keeps timing stalls and summarizing plans, but never lowers,
prompts or shows a notice; leaving the room starts fresh history with the
startup grace over. A lowering is never queued: while another replan, seek
recovery or track change is in flight it is refused and watched again later.
The phone holds a **Restore quality** that meets one until it ends, dropping
the kept estimate at once; TV merges it into the staged request. An adoption
the controller did not ask for (a track change, a recovery) drops a pending
lowering silently.

The controller's answer (**Keep quality**, **Restore quality**), its "Can't
lower quality" notice and its re-offer wait belong to the title. Another
attempt at the same title (Retry, a 404 renewal, failure recovery) keeps them;
other content resets them. A quality change clears them: a pick in the Quality
menu, a version the viewer picks, or the phone's move to the quality pair of
the network it is now on.

When the server advertises `playback_plan_summary_v1`, each plan that stops
playing is reported in a `plan_summary` route event: its stalls, delivered
bitrate, the rate its media arrived at (`throughput_kbps`, see 3.6), mode,
last buffering action, and the estimate the request that made the plan carried
(a seek reanchor's own, for its plan). Every stall's time counts in the total
and the longest; only stalls of 1 s or more count in `stall_count`. A pause,
seek or reload ends a stall uncounted, an outage drops its time, and a stall
still running when its plan ends counts. Until the plan first plays, a reload
or seek is part of the start's wait, which is timed from the first wait; a
pause before then ends the current wait uncounted without ending that. An
outage ends only when the plan's media data arrives again or playback runs; a
playlist or subtitle load that succeeds, or the network coming back, does not
end it, and an adopted plan starts with none. A failed plan is reported when the error shows.

Only running out of data is a stall. Media3 also reports `STATE_BUFFERING`
when its decoder falls behind and drops to the next keyframe, with seconds of
media still buffered. The adapter reports a wait that begins with more than
`RENDERER_WAIT_BUFFER_MS` (1 s) buffered past the playhead as a renderer wait,
which the controller does not count, and turns it into a stall if the buffer
falls to that threshold before playback resumes, checking every 200 ms while
the wait lasts. A tunneled player's waits are always network waits: its
buffered duration includes samples already in the codec, which does not fall
behind that way. A phone subtitle or cue refresh rebuilds the media item and
marks a reload, which is not a stall. A buffering pill waits, its timer
frozen, while an intro, recap, credits or Next Up pill shows, while the TV HUD
is open, while the player is in picture in picture and, on the phone, while
Silo is in the background. A display-only notice shows for 5 s of visible
time, waiting while a lifecycle notice holds the slot or the player is hidden.

The startup stall detector (section 8) treats a starved decoder, buffering with
under 5 s buffered ahead, as a transport stall. While data still arrives, that
stall belongs to the buffering controller, which lowers quality, rather than to
a same-plan reopen.

The server remembers what a lowering learned. A start or replan
carrying `bandwidth_estimate_source: "observed_rebuffer"` records a learned
limit for this user, device (`X-Silo-Device-Id`) and connection for 7 days from
the last observation, and Auto plans under it. A plan the limit lowered carries
`playback_plan.bandwidth_limit`, and the Quality menu labels Auto “Auto ·
limited after buffering”. **Restore quality** sends `learned_limit: "clear"`,
and `never` mode sends `learned_limit: "ignore"` on every start and replan, so
a limit learned by another profile on this device does not reach it. The
session manager reads the mode from the profile's stored setting on each
request, so a start never goes out before the mode is known. Cast requests
always send `learned_limit: "ignore"` and no estimate: this device's limits
are not the receiver's. Settings →
Playback shows the limit for the current connection with a **Reset**
(`DELETE /api/v2/playback/connection/learned-limit`). Only a server that
advertises `buffering_quality_v1` gets `buffering_quality_v1` in
`client_features`, `bandwidth_estimate_source`, and `learned_limit`;
`gatedForBufferingQuality` strips them for any other server and keeps the
estimate, and nothing is learned there.

### 3.6 Network inputs

A request sends `bandwidth_estimate_kbps` in two cases only:

- **The probe estimate, on a start.** A start sends the highest current
  reading, a speed test or a playback sample, with
  `bandwidth_estimate_source: "probe"`. It is a hint:
  the server uses it only to rule out a source above 80% of the estimate, and
  only when playback starts. Replans never carry it.
- **The step-down estimate, on lowering replans.** A replan that lowers Auto
  after repeated rebuffering sends `floor(delivered bitrate × 7 / 10)` with
  `bandwidth_estimate_source: "observed_rebuffer"`. Later Auto replans in the
  same playback keep sending that estimate, without a source, so a failure or
  track replan does not lift it. The carry ends with **Restore quality**, a
  quality change the controller did not send, or a new playback attempt.

Media3's bandwidth meter is never sent. It times loads that stall on a full
buffer or wait on a live transcode, so its figure can understate the link and
make `auto` convert the next title. `PlaybackNetworkMonitor` reports it only in
route events and the diagnostics bundle.

The speed test follows the server design's *Connection speed test*. One
`BandwidthProbeScheduler` per process (`android-shared/.../player/probe/`)
runs it, and `SpeedTestMeter` (`shared/`) holds its arithmetic, which
`BandwidthProbeVectorsTest` checks against the spec's `probe_cases`:

- **Endpoint.** Only for a server whose capability `features` carry
  `connection_speed_v1` and that names a `bandwidth_probe_url`, the probe on the
  path this client's media takes. That URL is the server's own and goes out
  with the app's normal auth. A lookup over the local route names the
  server's LAN address, so the client moves that URL back onto the saved
  address: there it carries the app's credentials, and the route still takes
  it, without reading the body ahead. A redirect to a short-lived proxy URL is
  followed once, without credentials. The same capability response names the target
  (`bandwidth_probe_target_kbps`), whether automatic tests are on
  (`bandwidth_probe_automatic`) and the reading lifetime
  (`bandwidth_probe_max_age_seconds`, 7 days when absent).
- **Measurement.** One download over one connection, timed in 500 ms windows
  from the first byte. Window 0 is TCP ramp-up and never counts. A ticker
  closes each window on the clock, so a window with no bytes counts as 0. The
  quick test asks for 100 MB and stops at the end of the first window after
  window 0 that reaches the target, reporting the target as a lower bound;
  otherwise it reads 3 s and reports the rate over the last 1 s. The accurate
  test asks for 1 GB, reads 10 s and reports the rate over the last 3 s. A
  link fast enough to deliver every requested byte before any window after
  window 0 completes (100 MB in under a second) reports the whole-transfer
  average instead, a lower bound, or the target when a quick test's average
  reaches it. A rate above 1 Gbps is reported as 1,000,000 kbps, a lower
  bound. A result under 100 kbps, a target under 100 included, measured
  nothing. A probe with no byte in 10 s is abandoned.
- **When.** A check runs on sign-in, a return to the foreground, a switch to
  another profile (its target follows the profile's libraries), a network
  change or the network being validated again, and at no other time. None
  runs under a borrowed TV identity (a remote or Cast hand-off). It reads this device's readings for the
  connection (`probe` and `playback_sample` in
  `GET /api/v2/playback/connection`) at once, so a playback start in the first
  seconds already sends them. Only the quick test waits, 5 s, and then runs
  only if automatic tests are on, no current reading proves the link, and it
  still may (nothing plays or starts, and no `429` from that server is waiting
  out its `Retry-After`). A `429` holds that server's automatic tests until
  its `Retry-After` (30 s when missing or unreadable, at least 1 s), across
  profile and account changes, while the process runs. A settled rate proves
  the link; a lower bound proves
  it only while it is at least the current target, so a raised target makes the
  test due again; without a target any current reading proves it. A check that
  fails or is refused waits for the next one. **Test speed** always runs the
  accurate test. Leaving the foreground stops it.
- **When not.** Never on a metered or cellular network, by the rule that picks
  the phone's cellular pair (`usesCellularQuality`, which ignores a VPN's own
  metered flag), and never while local media plays or loads. The process's one Media3 player
  (`ActivePlayerHolder`) is the playing signal. A video start also tells the
  probe, which cancels a running test and holds off automatic tests for 60 s.
  A check still reads the server's readings while media plays, since the next
  start needs them, and **Test speed** runs as soon as nothing plays.
- **Reading.** A probe and a playback sample, each current while younger than
  the lifetime, for the server, network and local route they were taken on. A
  reading outside 100–1,000,000 kbps, the bounds of `bandwidth_estimate_kbps`,
  is never kept, the server's or this device's, and one dated ahead of the
  device's clock counts as now. A start sends the highest current one only to
  that server, and only when its media takes that route (a Watch Party start
  stays on the saved address). A network change (lost, or another network
  handle, transport, metering, VPN state, or this server's local route coming
  or going) or a server change discards the readings in hand, and the next
  check looks for the new connection's.
- **Reporting.** Each result goes to
  `POST /api/v2/playback/bandwidth-probe/results` with its transport
  (`wifi`, `ethernet`, `cellular` or `other`) and `lower_bound: true` when it
  is one. It is kept for starts only once the server stored it, or when the
  POST got no answer; a result the server refused is never kept. A playback
  start or leaving the foreground does not cancel the POST.

Playback measures the link too. `PlaybackThroughputMeter` listens to the
player's video data sources. A request receives from its first read to its
last, and only a request that received for at least 1 s counts, its bytes after
the first read and its time; time in which several counted requests were
receiving counts once. A short request on a warm connection, such as one HLS
segment, arrives at the connection's burst rate, which on the sandbox was about
twice what the speed test sustained. Each `plan_summary` sends the figure as
`throughput_kbps` and resets it; with no counted request, or under 100 kbps,
it sends nothing. A plan adopted with no plan before it resets the figure
first, so it counts only from its own adoption. The server keeps the highest
as this device's `playback_sample`. A progressive
request held open while the buffer is full reads low, which keeps the figure a
lower bound.

Settings → Playback → Connection, shown only while the server advertises
`connection_speed_v1`, displays the latest probe from
`GET /api/v2/playback/connection` or this device's own stored one when newer,
“Faster than 115 Mbps” for a lower bound, and without a probe the playback
sample (“Faster than 51 Mbps · seen during playback”). **Test speed** runs the
accurate test in the foreground under the same gates, up to 10 s; when its
report fails, the row says so and keeps the server's reading. It also shows the
server's `auto_budget_kbps`, which the client displays and never computes. On
cellular and metered networks the note that speed tests are skipped replaces
the speed, and **Test speed** and the budget hide. Settings reads the
connection when Playback settings opens, after a network change while it is
open, and after **Reset** and **Test speed**. Rates round down: kbps below
1 Mbps, one decimal below 10 Mbps, whole Mbps from there, with no digit
grouping. Against a server whose settings manifest lacks
`playback.buffering_quality_mode`, Settings hides its row and the players run
`always`.

The phone sends its cellular pair (`playback.cellular_preferred_quality` and
`playback.cellular_max_bitrate_kbps`) as `quality_preference` and
`bandwidth_cap_kbps` when the active network has `TRANSPORT_CELLULAR`
(including a VPN running over mobile data), or when a network that is not a VPN
lacks `NET_CAPABILITY_NOT_METERED` (a phone hotspot, metered Wi-Fi). A VPN over
Wi-Fi gets the main pair: Android marks most VPNs metered by default. A network
change that holds for five seconds replans an active session with
`operation: "quality_change"` and the other pair, unless the viewer picked a
fixed rung in the player or the session is a download or a Watch Party. A move
waits while another replan or a track or quality change is in flight. A move
that fails or has to wait is tried again after 5, 15 and 30 seconds, from the
session and settings current then, unless the network changes again first.
Track and quality picks made while a move is in flight are applied once it
finishes. Android TV always sends the main pair. Against a server whose settings manifest
predates revision 20 the cellular pair is kept and applied on the device only.

An Auto picked in the player sends `auto` with the saved bitrate cap for the
current connection (no cap for No limit). It is not a fixed pick: the session
keeps following network changes, with only the cap moving. A subtitle or audio
change keeps the session's quality and cap. A renewal,
recovery, or reload of the same title carries only a quality the viewer picked
in the player or the route named; otherwise it reads the saved settings again
for the connection it is on. The next episode always starts from the saved
settings.

The Quality menu lights the row for the session's preference: Auto only when
that is `auto`, a picked rung its own row, and a saved resolution such as
`1080p` the delivered class's rung when a cap reduced it. When the source
plays as-is under a saved resolution, no row is lit, so the viewer can still
choose Auto, and the control names the resolution.

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
