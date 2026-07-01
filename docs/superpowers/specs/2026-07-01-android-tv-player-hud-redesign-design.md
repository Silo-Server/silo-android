# Android TV Player HUD Redesign

Date: 2026-07-01

## Context

The current Android TV player HUD is a fixed 550dp by 190dp overlay inspired by tvOS. On Shield-class Android TV devices that height is too small: the Info tab clips rows, and the other tabs feel cramped because tab chrome, padding, and pane content compete for the same shallow space. The tvOS HUD remains the taste baseline, but Android TV needs adaptive sizing and layout because dp scaling, resolution, and overscan-safe space differ from tvOS.

## Goals

- Make the player HUD feel premium, calm, and intentionally designed on Android TV.
- Fix clipping in the Info tab and prevent similar clipping in every HUD tab.
- Keep playback visible behind the HUD without turning the HUD into a full-screen settings page.
- Preserve all current functionality: Info, Stats, Video, Audio, Subtitles, Chapters, pickers, subtitle search, AI translation, sleep timer, and chapter selection.
- Improve D-pad behavior so tabs, rows, pickers, and chapter selection are predictable.
- Use tvOS as visual guidance, but allow Android-specific sizing and structure.

## Non-Goals

- Do not redesign the bottom playback controls in this pass.
- Do not remove Android-only subtitle search or AI translation controls.
- Do not introduce request, watch-together, or admin entry points into the player HUD.
- Do not change playback engine routing, subtitle rendering behavior, or stream selection logic.

## Design

### Shell

Replace the fixed-height HUD with an adaptive centered panel:

- Width: approximately 680dp on standard TV layouts, constrained to about 80% of the viewport.
- Height: content-safe range instead of a hard 190dp, with a target around 300dp and a max around 62% of screen height.
- Position: top-center enough to avoid covering subtitles and the timeline, but low enough to remain comfortably readable.
- Surface: dark translucent/frosted panel with a soft border and restrained shadow. Keep video visible behind it.
- Internal structure: tab rail at top, then a content viewport with a stable min height.

The panel must never clip default tab content. If a tab has more rows than fit, the tab content scrolls with visible focus movement and enough bottom padding that the focused row is not cut off.

### Tab Rail

Keep the tab set in this order: Info, Stats, Video, Audio, Subtitles, Chapters.

Use compact tab pills with better spacing and consistent focus states:

- Focused: white pill, black text.
- Selected but unfocused: muted light pill, white text.
- Idle: low-alpha dark pill, readable text.

The row may horizontally scroll only if needed on narrower displays. Default Shield/Streamer layouts should show all tabs without looking compressed.

### Info Tab

Use a two-column summary:

- Left column: title, season/episode tag, runtime.
- Right column: resolution/HDR badges and rows for route, video codec, audio codec, subtitles, and current chapter.

Rows use compact typography but enough line height for TV readability. The default view must show the current stream facts without cutting off the Chapter row.

### Stats Tab

Convert the raw list into grouped rows:

- Playback: backend, state, speed.
- Stream: resolution, video codec, audio codec, HDR.
- Buffer/network: buffered ahead, bandwidth, dropped frames/underruns if present.

Only render available values. If no stats exist, show a quiet empty state.

### Video Tab

Use setting rows that open focused pickers:

- Quality
- Speed
- Aspect
- HDR passthrough
- Auto-skip intro
- Auto-play next
- Sleep timer

Sleep timer should not be crammed into tiny chips. Present it as a setting row that opens a preset picker and includes "Off" or "Cancel" when active.

### Audio Tab

Keep this tab intentionally small:

- Audio track
- Audio delay

Audio delay should open a picker/stepper with clear negative and positive values. The selected value must remain visible after closing.

### Subtitles Tab

Split into two columns on wide screens:

- Tracks and timing: subtitle track, subtitle delay.
- Appearance/actions: size, font, background, opacity, position, search subtitles, AI translate.

Delay should use plus/minus style controls or a focused picker that is not visually oversized. Track selection should close predictably after selection.

### Chapters Tab

Use a scrollable chapter list with:

- Chapter title.
- Time position.
- Current chapter highlight.
- Center/select seeks to that chapter and closes the HUD or returns focus to playback controls consistently.

The list must not trap focus at the bottom.

## Focus And Back Behavior

- Initial focus goes to the selected tab.
- Left/right moves across tabs.
- Down enters the active pane.
- Back closes an active picker first, then closes the HUD.
- Center/select on setting rows opens pickers.
- Center/select on picker options commits and closes the picker.
- Center/select on chapter rows seeks and dismisses or returns focus predictably.

## Implementation Notes

- Keep changes localized to `TvPlayerHud.kt` plus focused tests.
- Introduce small layout constants for HUD width, height, padding, tab height, row height, and spacing.
- Prefer shared row primitives for setting rows and value rows so Video, Audio, and Subtitles look related.
- Avoid nested decorative cards inside the HUD. The HUD is the card; tab content should be flat.
- Use source tests where Compose screenshot tests are not available, and keep unit tests for pure helpers such as tab visibility and formatter behavior.

## Testing

- Unit/source test that the HUD no longer uses a fixed `height(190.dp)` shell.
- Source test that the HUD shell has bounded adaptive height and scrollable tab content.
- Existing HUD tab visibility tests continue to pass.
- Build `:androidTvApp:assembleDebug`.
- Install on Shield or Android TV emulator.
- Manually inspect each tab:
  - Info shows all rows without clipping.
  - Stats scrolls if populated.
  - Video rows and sleep timer are readable.
  - Audio track/delay pickers work.
  - Subtitles track/delay/appearance/search/AI controls work.
  - Chapters list scrolls, selects, and exits cleanly.

## Acceptance Criteria

- No default HUD tab appears cut off on a 4K Shield screenshot.
- Info tab displays stream facts and current chapter without bottom clipping.
- Every tab has a coherent layout and readable typography.
- D-pad navigation works across tabs, pane rows, pickers, and back dismissal.
- Build and focused tests pass.
