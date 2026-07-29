# Android Subtitle Aspect Reconciliation and Phone Sizing Design

## Context

PR #127 makes the shared Android subtitle canvas follow the displayed video
area in Fit, Fill, and Stretch modes. On a physical Pixel running the PR head,
changing Fill to Fit reproduced a remaining defect: subtitle cues stayed
vertically displaced and were clipped below the display. Depending on the cue
and dialogue gap, subtitles appeared enabled but absent. Playback, subtitle
selection, cue delivery, and video decoding remained healthy, and subsequent
captures showed the same track rendering normally in Fill mode.

The same device validation also showed that the Android default subtitle size
is too small on a phone. The shared default is `Large`, but Android maps it to
`32 / 720` of the subtitle canvas, below Media3's default fractional size and
smaller than the shared model's nominal 56-point Large value.

## Goals

- Keep subtitle cues fully visible and correctly centered after every supported
  aspect-mode transition.
- Make every phone subtitle-size preset legible at normal handheld viewing
  distance while preserving the relative steps between presets.
- Preserve existing Android TV subtitle sizing.
- Preserve subtitle selection, cue styling, authored positioning, libass/ASS,
  bitmap subtitle, letterbox, and title-safe behavior.

## Non-goals

- Changing subtitle tracks, server subtitle processing, or playback protocols.
- Changing the shared preset names or persisted subtitle appearance schema.
- Changing Android TV's existing font-size scale.
- Reimplementing Media3's aspect-ratio measurement algorithm.
- Adding unbounded frame callbacks, polling, delays, or timeout-based layout
  workarounds.
- Reworking Android TV's existing subtitle remount transaction architecture.

## Design

### Stable post-layout reconciliation

`SubtitleVideoRectSync` remains the single owner of subtitle-view geometry. An
aspect change may expose old `exo_content_frame` bounds during the immediate
Compose `AndroidView.update` callback. Mobile Fit and Stretch must not convert
those transitional bounds into fixed pixel dimensions: they set the subtitle
child to `MATCH_PARENT` with zero margins, allowing the Media3 content frame to
remeasure the subtitle child automatically.

Mobile Fill maps to Media3 Zoom and still needs a parent-local visible crop
rectangle because its content frame extends beyond the viewport. That mode
continues to reconcile from the measured `exo_content_frame`.

If the snapshot changes during that traversal, one further pre-draw
reconciliation is scheduled. The operation is generation-bound and capped at
two post-layout passes for each explicit sync request. A newer request replaces
the older generation, repeated requests coalesce, and detach/dispose cancels
pending work. No callback remains installed after the rectangle is stable or
the bound is reached. At the bound, the latest measured rectangle remains
applied; the permanent content-frame layout listener still handles any later
real layout change without spinning.

The sync continues using Media3's measured `exo_content_frame` instead of
duplicating its aspect calculations. Fixed geometry remains expressed in the
subtitle view's parent-local coordinate space. Television title-safe and
letterbox insets retain their existing fixed-rectangle behavior; the
`MATCH_PARENT` shortcut applies only when both insets are absent.

### Phone-only subtitle scaling

Font-size conversion will accept an explicit Android presentation class:
`Phone` or `Television`. Phone uses a 1.125 multiplier over the current
fractions:

| Preset | Phone | Television |
| --- | ---: | ---: |
| Small | 22.5 / 720 | 20 / 720 |
| Medium | 29.25 / 720 | 26 / 720 |
| Large | 36 / 720 | 32 / 720 |
| XLarge | 45 / 720 | 40 / 720 |
| XXLarge | 54 / 720 | 48 / 720 |

The phone and TV dependency-injection modules construct `SubtitleManager` with
their fixed presentation class. The persisted preset remains unchanged, so an
existing `Large` preference becomes more legible on phone without a migration
and retains its current appearance on TV.

Fractional sizing remains relative to the active subtitle canvas. It therefore
continues to respond naturally to orientation and displayed-video bounds.

### Initial subtitle restore settlement

On phone, restoring a persisted mounted subtitle must not treat Media3
`Player.STATE_READY` as proof that its text-track catalog has settled. Media3
can report ready while publishing an intermediate non-empty text-track
snapshot; failing the restore against that first snapshot produces a transient
error even though the requested track appears moments later.

The phone player will follow the existing TV settlement rule: the first
non-empty text-track snapshot is provisional, a changed snapshot restarts
settlement, and only a repeated identical non-empty snapshot may prove that a
requested track is missing. A successful identity match still commits
immediately. The existing bounded mobile mount timeout remains the terminal
fallback when no stable success arrives.

Android TV already implements this rule through
`TvSubtitleSnapshotSettlementTracker` and `SubtitleRemountReselection`; its
production path remains unchanged and receives focused regression coverage.

## Correctness and lifecycle constraints

- Immediate synchronization remains available for already-stable layouts.
- Reconciliation reads the current player, resize mode, video size, and content
  frame on every pass; it must not apply a rectangle captured for an older
  mode.
- At most one pre-draw listener exists per `PlayerView`.
- Detaching the view removes listeners and prevents late mutation.
- A replaced player cannot receive or influence later reconciliation.
- Existing cue forwarding and libass overlay attachment remain unchanged.

## Testing

Unit and mounted Robolectric coverage will prove:

- Fill to Fit and Stretch to Fit settle to the final parent-local rectangle
  without retaining a cropped top/left margin.
- Fit to Fill and rapid Fit/Fill/Stretch changes use the latest mode.
- A changed content-frame snapshot receives the bounded second pass.
- Stable geometry uses no extra pass, repeated explicit syncs coalesce, and
  detach cancels pending work.
- Every phone preset is exactly 1.125 times its TV fraction.
- The default `Large` preset resolves to `36 / 720` on phone and `32 / 720` on
  TV.
- Phone and TV construction paths select their intended presentation class.
- Mobile Fit and Stretch apply `MATCH_PARENT` dimensions and zero margins when
  no title-safe or letterbox inset is configured.
- A stale Zoom crop followed immediately by Fit cannot retain its top/left
  offsets, even before the Media3 parent completes its new layout.
- A restored phone subtitle cannot fail on the first non-empty Media3
  text-track snapshot, and a changed snapshot must stabilize again before it is
  terminal.
- A matching restored phone subtitle commits as soon as it appears; a track
  that never appears still fails through the existing bounded timeout.
- TV's first-snapshot and changed-snapshot settlement regressions remain green.

Focused shared, phone, and TV subtitle tests will run first, followed by the
full unit suite and phone/TV release assemblies. Physical validation will use
the Pixel only and exercise Fit, Fill, Stretch, rapid transitions, multi-line
cues, and cue gaps. The Shield will not be installed or modified without
separate authorization.

## Success criteria

- The reproduced Fill-to-Fit cue is fully visible immediately after the sheet
  closes and remains visible across subsequent cues.
- No supported aspect transition leaves stale subtitle margins or dimensions.
- Default phone subtitles sit between the original undersized build and the
  rejected 1.25× build while all phone presets remain ordered and selectable.
- Restarting playback with a persisted subtitle does not show a transient mount
  error while Media3 is still publishing text tracks.
- TV output, persistence, selection, styling, and subtitle formats show no
  regression in automated verification.
