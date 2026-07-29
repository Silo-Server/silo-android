# Android Player System Brightness Design

## Decision

The Android phone video player will stop overriding window brightness. Android
system and adaptive brightness remain authoritative while playback is open.

## Current problem

`PlayerGestureHandler` assigns a vertical drag beginning in the left 88 dp edge
to brightness control. The first drag converts the system-managed window value
into a fixed per-window brightness and later drags continue changing that
override. While the player is visible, Android's normal brightness control
appears ineffective because the window override wins.

## Behavior

- Remove the left-edge brightness drag mode and all writes to
  `WindowManager.LayoutParams.screenBrightness`.
- A vertical drag beginning in the left edge performs no brightness action.
- Preserve the right-edge volume gesture.
- Preserve center swipe-down dismissal, double-tap seeking, pinch aspect-mode
  changes, control toggling, and temporary fast-forward.
- Preserve `FLAG_KEEP_SCREEN_ON` while playing or buffering; it prevents sleep
  and is independent of brightness.
- Do not change Android TV behavior, system settings, permissions, or adaptive
  brightness.

## Implementation

Remove the `Brightness` member from `VerticalDragMode`, the
`adjustBrightness` helper, and their now-unused Android window imports. Retain
the left/right edge boundary only for routing the right edge to volume and the
center region to dismissal. The left edge resolves to `None`, so it cannot
accidentally dismiss playback.

## Verification

Automated coverage will prove that the mobile gesture implementation contains
no window-brightness mutation while retaining volume and dismissal routing.
Focused mobile player tests and the phone release assembly must pass. A Pixel
smoke check will confirm that Android's brightness control remains effective
during playback and that right-edge volume and center dismissal still work.
The Shield will not be installed or modified.

## Success criteria

- Opening and using the phone player never creates a per-window brightness
  override.
- Android system/adaptive brightness continues controlling the display during
  playback.
- Existing non-brightness player gestures and keep-screen-awake behavior do not
  regress.
