# Silo Android Playback Architecture

Status: **the Android client is ported to the platform-neutral playback-v3
wire contract; live validation still requires a server built from the matching
neutral-v3 revision**.

This directory owns the Android Media3 runtime and its validation history. The
normative wire contract is the server repository's
`docs/architecture/playback-protocol-v3.md`; when these older migration notes
disagree with it, the server contract wins. In particular, Android no longer
advertises engine names or computes plan-attempt keys.

## Product decision

Silo Android will ship one in-process video engine: **Media3 ExoPlayer**. MPV
will be removed from app artifacts, capability reporting, engine selection,
recovery, and UI.

This is a new routing and compatibility architecture, not a replacement for
every Android player component. The retained foundation and target runtime are
defined in [architecture section 2](01-media3-only-player-architecture.md#2-target-runtime).

Media3 is the AndroidX media framework; ExoPlayer is its default `Player`
implementation. Silo already uses that implementation. [Checkout dependency](../../android-shared/build.gradle.kts) ·
[player factory](../../android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlayerFactory.kt)
See the
[Media3 ExoPlayer overview](https://developer.android.com/media/media3/exoplayer)
and [migration guide](https://developer.android.com/media/media3/exoplayer/migration-guide).

## Document ownership

| Document | Canonical content |
| --- | --- |
| [Architecture](01-media3-only-player-architecture.md) | Android runtime invariants plus the pre-neutral contract history. Neutral wire semantics come from the server contract. |
| [Migration and validation](02-migration-compatibility-validation.md) | Historical Media3-only migration plan, hardware fixtures, and rollback evidence. |
| [Reference review](03-reference-implementation-review.md) | Source-pinned Wholphin/Plezy observations. It is evidence, not another implementation plan. |
| [Implementation status](04-implementation-status-and-dv-handoff.md) | Code, automated proof, dev-server v3 status, and the 4K Dolby Vision handoff checklist. |
| [Intro skip](intro-skip.md) | Where the never/ask/always prompt lives, and the rules the server spec pins. |
| [Watch Party implementation plan](../watch-party-implementation-plan.md) | Room contract, player binding and catch-up, phone/TV experience, milestones and qualification. Server documents own wire semantics. |
| [Shield 1080p capability audit](05-shield-1080p-playback-capability-audit.md) | Live protocol-v3 route matrix, catalog coverage, current direct-play gaps, and prioritized causes. |
| [Device-correction evidence and design](06-device-quirk-evidence-and-design.md) | Current Jellyfin Android TV, Jellyfin Android, Wholphin, Plezy, Android platform, and issue evidence for the server/client quirk layer. |

Requirements are defined once in their owning document. Other documents link to
that section instead of restating it; this prevents future agents from treating
two similar passages as separate requirements.

The implementation-status document records proof and remaining gates; it does
not create another set of requirements.

## Release gate

The neutral Android build must not ship until a named server revision exposing
the matching platform-neutral v3 contract is published and deployed. A
pre-neutral `playback_plan_v3` server is not compatible merely because the
feature token has the same name.

## Evidence boundary

- **Current-code statements** must cite this checkout or the matching server
  revision.
- **Framework statements** must cite pinned primary sources where versions
  matter.
- **Device outcomes** remain unproven until tested on the named device, display,
  HDMI/eARC chain, and AVR. A decoded frame alone does not prove correct HDR or
  passthrough output.

## Native embedded subtitles

Android advertises `embedded_subtitles_v1` only when the original-HTTP delivery
includes a `native_embedded` capability. Both supported entries use
`container_track_id`; FFmpeg stream indexes are not Media3 track IDs.

- MP4 with `mov_text`: Media3 1.11.0 sets a timed-text format's ID from the MP4
  `tkhd` track ID. See the pinned [Media3 MP4 parser](https://github.com/androidx/media/blob/1.11.0/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/BoxParser.java).
- MKV with `subrip` and `ass`: `MatroskaExtractor` sets each track's ID to its
  Matroska `TrackNumber`. See the pinned [Media3 Matroska extractor](https://github.com/androidx/media/blob/1.11.0/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java).
  Remuxed files keep their original track numbers, so numbers can have gaps
  and do not equal stream index + 1. The `ass-media` extractor subclass passes
  formats through unchanged, so ASS keeps the same ID. The MKV entry's
  `ass_styling` and `font_attachments` report whether libass renders the stream
  with its attached fonts; when they are false and the user prefers preserved
  styling, the server keeps ASS on its sidecar path.
- MKV with `hdmv_pgs_subtitle`, `dvd_subtitle` and `dvb_subtitle`: the same
  extractor exposes `S_HDMV/PGS`, `S_VOBSUB` and `S_DVBSUB` tracks, and
  Media3's `DefaultSubtitleParserFactory` decodes them. In direct play a PGS
  track no longer needs the server's `.sup` extraction, and VobSub and DVB no
  longer need a burn-in transcode. Their inventory rows still say
  `burn_in_only`, because the server has no sidecar for them; the plan's
  `subtitle.embedded` is what makes them native. A remembered VobSub or DVB
  track is restored as `embedded` and the server decides its route again.

The server must record each Matroska subtitle's `TrackNumber` as
`container_track_id` (proposed in Silo-Server/silo-server#1890). Tracks without
a recorded ID keep using sidecars. MKV WebVTT (`S_TEXT/WEBVTT`) is not
advertised because FFmpeg does not recognize that codec, so the server cannot
identify those tracks. Media3 drops tracks with the legacy `S_ASS` and `S_SSA`
codec IDs, which FFmpeg still reports as `ass`; the server records no ID for
them, so they stay on sidecars too.

Known cost of the stream path: after a seek or resume, a cue that began before
the cluster Media3 resumes from stays hidden until the next cue.

A plan's `subtitle.embedded` selects the exact container track. Inventory URLs
remain fallback descriptions and are not mounted alongside that selection.
Phone and TV commit the preference only after the native track is mounted.
Missing, ambiguous, or unsupported identities produce `subtitle_embedded_failed`
and let the server replan with its sidecar path. Tracks without a probed
container ID keep using sidecars; metadata similarity is not exact identity
evidence.

## HLS subtitle renditions

Android sends `hls_subtitle_renditions_v1` when its HLS delivery carries
`native_hls_playback_v1`, so the Cast context never negotiates it. It adds
`hls_remux_subtitle_renditions_v1` only when the server's `/api/v2` capability
`features` (or a decision's `server_features`) advertises it; both are
attempt-sticky on the server. A `server_transcode_hls` plan, and with the remux
feature a `server_remux_hls` plan, with `subtitle.hls_rendition` carries the
selected embedded text track as a WebVTT `SUBTITLES` rendition of its
multivariant playlist instead of a whole-file sidecar extraction. The selected inventory row keeps its server URL on the wire, but the
client projects it with `hls_rendition` and no URL, so it is never mounted as a
sidecar; other rows keep their URLs.

Media3 1.11.0's `HlsPlaylistParser` names an `EXT-X-MEDIA` rendition
`GROUP-ID:NAME` (`subs:Subtitles`) and labels it `NAME`; `HlsMediaPeriod` keeps
both, and a merged source adds a numeric child prefix. Phone and TV select that
exact ID, falling back to a single non-sidecar track labelled `NAME`. Language
never identifies the rendition, which declares none, and an unlabelled in-band
CEA-608 track never matches. Like a native embedded decision, the preference
commits only after the track is selected. A mount that fails replans
`failure_recovery` with `subtitle_hls_rendition_failed`; the failed plan key
makes the server return the sidecar plan. Rendition cues carry
`X-TIMESTAMP-MAP`, so Media3's timestamp adjuster aligns them and they receive
only the user delay, like embedded cues.

With `hls_subtitle_renditions_all_v1`, sent under the same rule as the remux
feature, the server lists every embedded text track its index can read as a
rendition of one `subs` group. Each such inventory row carries
`hls_rendition: {group_id, name, index}`, and the client projects every one of
them, not only the selected row, as a rendition with no URL. A rendition with
an `index` is how the client knows the stream lists every track.

Sidecar cues use absolute source timestamps. Android subtracts the plan's
`timeline_offset_seconds` once, alongside the user's subtitle delay. Embedded
cues already follow the extractor timeline and receive only the user delay.

Cast uses the default receiver, which renders VTT in the transport clock. At a nonzero video timeline offset, the VTT URL adds `timestamp_offset=-timeline_offset_seconds`, preserving the file/provider identity and stream authorization query parameters. The server shifts the canonical source-time cues for that response; native Android renderers continue applying their local subtitle offset.

## Switching subtitles on the playing stream

A pick of a track the playing media already carries is shown at once and
reported to the server, instead of waiting for a replan and a remount. Two kinds
of rows qualify, and Off qualifies on the same streams
(`canSwitchSubtitleInPlace`):

- On `original_http`, an embedded row the server marks `native_selectable`
  (contract C1), switched by its `container_track_id`. A server that sends
  `stream_index` omits `native_selectable` exactly when it is false. Without
  either field the client evaluates the row with the rules `validateForMedia3`
  applies to a native plan, which needs a `container_track_id`, so in practice
  only rows a newer server describes switch locally. ASS is left out of that
  fallback because its native route also depends on the fidelity preference
  and libass.
- On an HLS transcode or copy remux, a row with an every-track rendition,
  switched by Media3's `GROUP-ID:NAME` id.

Each row becomes an exact `SubtitleIdentity.Embedded`, and the player must also
list the track (`isLocallyMountable`). A pick that changes audio or quality as
well, or a track the media does not carry, takes the ordinary replan.

**Flow.** The adapter (phone and TV) starts a local selection for the pick and
sends its `track_change` replan in the same step. The player applies a
`TrackSelectionOverride` (or turns text off), and the mount report marks the
selection `mountedBeforeAdoption`. A mount deadline that fires while the
replan is still out does not fail the switch: the server's answer decides.
When a subtitle pick's replan is staged (`subtitle_track_changed`, never a
quality, output or buffering change), `PlaybackSessionManager` marks the
candidate `Ready.inPlace` when it keeps the transport the player renders
(`appliesInPlaceOver`): the same session, stream URL, protocol, headers,
effective file, recipe, timeline offset and origin, audio (on
`original_http`, the audio the player switched to on its own counts as the
plan's), client transformations, runtime corrections, quirks and decision-reason claims, and
only a subtitle decision the player selects in that media (Off, a native
`subtitle.embedded` on `original_http`, or a rendition of an unchanged
rendition set on HLS). The server's plan id and attempt key still change,
because the selected subtitle is part of its plan identity.

**Adoption without a remount.** An `inPlace` plan becomes the active plan in
the manager with the transport's first-frame report, local mutations and
passthrough-suppression scope carried over, since none of them restart. The
view models show `withInPlaceAdoption`: the mounted plan with the adopted
plan's subtitle decision, identity and menus laid over it, so what the client
changed on the mount itself (a PCM retry's audio claim and decision trace)
stays. Phone adopts it without `expectNextMediaMount()`, so its mount effect, gated on the
awaited mount generation, leaves the MediaItem alone. TV adopts it without a
mount nonce, and its mount effect keys on `PlaybackExecutionPlan.mediaMountKey()`,
which drops the subtitle decision, plan id and replan-time position; every
other plan change still comes with a new nonce. The stall detectors key on
`mountedPlaybackKey`, which leaves out the plan id, so they keep watching the
same mount. The buffering-quality controller keeps the transport's plan,
because no new transport starts, so an in-place plan gets no `plan_summary`
of its own: the transport's summary names the plan that started it and
covers the time on the adopted subtitle too. The server expects nothing per
plan. It keeps a summary as the connection's latest, keys throughput samples
and learned limits by connection, and uses the plan id only to label the
stall-ratio metric with the plan's delivery, which it resolves for any plan
the attempt has had and which an in-place switch leaves unchanged.

**Confirmation on TV.** TV publishes a replan in two phases: the manager and
lifecycle hold the replacement as pending until the player proves the
selected subtitle mounted, then the adapter's settlement confirms it
(`confirmVideoSessionPublication`) or rolls it back. A remount used to supply
that proof through the mount nonce. In place, the adapter installs a new mount
owner for the adopted identity even when it is the identity the local switch
already selected; the snapshot carries `localMountGeneration`, the view model
re-arms its remount reselection whenever the owner changes, the reselection
resolves against the tracks already published, and the confirmed selection
settles the publication. Phone needs no second phase: it publishes on commit
and confirms the selection against the current mount.

**Failure and rollback.**

- The replan fails, or a newer pick that needs a replan replaces the switch
  before it lands: the player goes back to the subtitle the server still has.
  On phone the screen reselects the committed identity once the local mount
  ends. On TV the adapter restores it (Off included), and when the switch
  ends with no local owner after it, the snapshot's `localMountInPlaceSwitch`
  tells the view model to reselect the committed identity.
- The replan returns another transport, or a subtitle the media does not
  carry, such as a sidecar: `inPlace` is false and the plan is adopted with a
  remount, as before.
- An in-place publication on TV is rolled back (its mount is not confirmed, or
  a newer pick supersedes it): the view model restores the predecessor's plan,
  quality menu and rows and reselects its subtitle on the same tracks, without
  a remount. A publication that remounted rolls back with a remount, as
  before.
- The adopted track does not mount: the usual failure path replans
  `subtitle_embedded_failed` or `subtitle_hls_rendition_failed` for the
  sidecar plan.

A switch on the playing stream leaves the previous sidecar in the MediaItem
until the next remount. TV counts a server sidecar as locally mountable only
while the plan selects it (`isTvLocallyMountable`), so picking that stale
sidecar again goes to the server instead of committing locally.

After an in-place rendition switch the multivariant playlist still marks the
original track `DEFAULT=YES`, so the player never reloads it for the switch.
A later remount for another reason reselects the committed identity
explicitly: phone re-applies it, and TV's same-route remount re-arms the exact
`Embedded` identity rather than a sidecar ordinal.
