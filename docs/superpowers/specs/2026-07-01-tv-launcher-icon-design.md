# Android TV Launcher Icon Design

## Goal

Make the Android TV launcher icon look intentional on Google TV Streamer, whose launcher masks app icons into circular tiles.

## Decision

Use a proper adaptive icon:

- Keep `@color/silo_icon_background` as the adaptive icon background.
- Replace adaptive `ic_launcher_foreground.png` assets with transparent mark-only PNGs.
- Do not bake a rounded-square blue tile into the adaptive foreground.
- Render legacy `ic_launcher.png` density assets as circle-friendly blue fields with the same centered mark, because Google TV's app row can use/mask the legacy icon even when an adaptive icon exists.

## Rationale

The current foreground contains a complete rounded-square tile. Google TV then applies its own circular mask, producing a cropped tile-inside-circle look. A mark-only transparent foreground lets the launcher own the adaptive mask while Silo keeps its blue brand field and centered glyph. On the Google TV Streamer app row, the launcher also appears to use the legacy icon path, so the legacy TV launcher PNGs must be circle-friendly as well.

## Verification

- Source/unit test confirms the adaptive foreground is transparent outside the mark and bounded inside the safe zone.
- Source/unit test confirms the legacy TV launcher icon has transparent corners and an opaque center field.
- Build the TV APK.
- Install on Google TV Streamer.
- Screenshot the launcher and confirm the Silo icon no longer shows a cropped square tile.
