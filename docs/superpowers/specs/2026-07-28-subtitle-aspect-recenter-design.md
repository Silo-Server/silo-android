# Subtitle Aspect-Mode Recentring Design

## Problem

On Android phone, changing video gravity from Fit to Fill or Stretch can leave
the subtitle layer using the previous fitted-video geometry. The visible video
then fills the player while subtitles remain positioned against stale bounds,
so ordinary centred subtitles appear off-centre.

The phone and TV players both use `SubtitleManager` to align Media3, libass, and
bitmap subtitle rendering with the visible video viewport. Phone is the
confirmed reproduction. TV must receive the same shared correction because it
uses the same geometry owner, while its existing player wiring must be verified
independently.

## Intended Behaviour

- The subtitle canvas follows the final visible video viewport after every
  aspect-mode change.
- Fit aligns the canvas with the fitted video rectangle.
- Fill and Stretch align the canvas with the full visible player viewport.
- Switching modes repeatedly cannot retain geometry from an earlier mode.
- Ordinary centred SRT/WebVTT cues remain centred in the new canvas.
- Authored ASS/SSA and PGS positions remain relative to the canvas. The client
  does not rewrite individual cue positions or force every cue to centre.
- Existing letterbox detection, title-safe insets, subtitle appearance, and
  transactional subtitle selection remain unchanged.

## Design

`SubtitleManager` remains the single geometry owner. Aspect-mode consumers
continue setting `PlayerView.resizeMode` and requesting a subtitle-bound sync.
The synchronizer must resolve bounds from the current resize mode and the
post-layout content frame, and it must schedule one bounded post-layout
reconciliation when a resize request can still expose the previous frame.

The reconciliation is idempotent: it computes the desired rectangle, compares
it with the current subtitle layout parameters, and writes only when dimensions
or offsets differ. It does not introduce polling, arbitrary delays, or a second
subtitle renderer.

The shared correction applies to both phone and TV. Platform screens retain
their existing aspect-mode mappings:

- Phone Fill maps to Media3 Zoom; Stretch maps to Media3 Fill.
- TV Zoom and Stretch retain their existing mappings.

## Lifecycle and Safety

Any posted reconciliation is owned by the existing `PlayerView` synchronizer.
It is cancelled or made inert when the view detaches or the synchronizer is
disposed. A stale callback must not update a detached or replacement player
view.

The change must not alter playback state, track selection, subtitle timing,
network requests, or persisted settings.

## Verification

Automated regression coverage will prove:

- Fit computes fitted-video bounds.
- Fit to Fill and Fit to Stretch settle on full-viewport bounds.
- repeated mode switching does not retain stale offsets or dimensions;
- authored cue coordinates are not rewritten;
- phone and TV aspect-mode update paths both request shared subtitle
  reconciliation;
- disposal prevents a delayed reconciliation from mutating a detached view.

Focused shared, phone, and TV unit tests will run before both release variants
are assembled. On-device phone verification will switch among Fit, Fill, and
Stretch with a centred text subtitle and confirm visual recentring. TV will be
verified through focused tests and compilation; no Shield installation is
required unless separately requested.

## Out of Scope

- Changing subtitle appearance, size, vertical presets, or delay.
- Repositioning authored ASS/SSA or bitmap cues.
- Server, protocol, transcoding, or subtitle-format changes.
- Replacing Media3 or libass subtitle rendering.
