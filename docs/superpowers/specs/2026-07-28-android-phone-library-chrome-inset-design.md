# Android Phone Library Chrome Inset Design

Date: 2026-07-28

## Goal

Match the current iOS Libraries tab behavior on Android phone: the library
selector, actions, and Recommended / Browse / Collections tabs remain fixed at
the top, while scrollable library content occupies only the space below that
chrome. Library rows, posters, and hero content must not scroll visibly behind
the menu controls.

This change is phone-only. It does not alter Android TV, standalone Browse or
Collections routes, Home, server APIs, navigation semantics, or the profile
menu's actions.

## Current Behavior and Root Cause

`LibrariesScreen` draws its tab content full-screen and then draws
`LibrariesFloatingChrome` afterward in the same root `Box`. The chrome uses a
partially transparent gradient. Recommended and Collections reserve initial
top runway inside their scroll containers, but that runway scrolls away; later
content therefore remains visible beneath the selector and tab controls.
Browse already uses fixed outer padding and does not exhibit the same underlap.

The iOS implementation uses a top `safeAreaInset`, which reduces the space
offered to every library tab's scroll view. Its content therefore starts below
the shared chrome and cannot pass behind it.

## Considered Approaches

1. **Reserved top chrome slot — selected.** Keep the existing custom Android
   chrome and full-screen backdrop, but place the chrome and tab-content
   viewport in a vertical layout. The chrome consumes its measured height and
   the content viewport receives the remaining height. This directly matches
   the layout semantics of iOS `safeAreaInset` without replacing existing
   controls.
2. **Stronger translucent scrim.** Leave content underneath but obscure it more
   once scrolling starts. This reduces visual noise but does not fix the
   reported behavior and remains inconsistent across tabs.
3. **Material `Scaffold.topBar`.** This also reserves layout space, but would
   introduce a larger structural and visual migration for a custom chrome that
   already works. It is unnecessary for this focused correction.

## Layout Design

The root retains its full-screen background and optional Recommended hero
backdrop. Above that background, a vertical foreground layout owns:

1. `LibrariesFloatingChrome`, including the status-bar inset, library selector,
   action buttons, profile popup anchor, subtab selector, and its bottom space.
2. A clipped, weighted content viewport containing loading, error, empty,
   Recommended, Browse, or Collections content.

Because the chrome participates in measurement rather than overlaying the
viewport, no tab needs a hard-coded `LibrariesChromeContentHeight` runway.
Remove the duplicate status-bar/chrome top padding from Recommended, Browse,
and Collections content. Each tab must still preserve its own internal spacing
and the existing bottom-chrome inset so its last item remains reachable.

The optional hero artwork may continue painting behind the entire screen,
including behind the chrome. Only interactive and editorial scroll content is
confined below the chrome. This preserves the visual relationship between the
hero and header without allowing text or cards to pass under menu controls.

## Interaction and State

- Library switching, tab switching, search, Requests, Watch Together,
  settings, profile/server switching, and sign-out behavior remain unchanged.
- Recommended retains its scroll position and hero selection behavior.
- Browse and Collections retain their filters, pagination, grids, and empty /
  error states.
- The profile popup remains anchored to the profile button. Opening or closing
  it must not change the content viewport or reset scroll position.
- System status-bar and display-cutout insets are consumed exactly once by the
  chrome.

## Verification

Tests and validation must cover:

- Structural/source coverage that all three canonical library subtabs share
  one reserved chrome/content boundary.
- Recommended and Collections no longer contain scrollable top runway used to
  clear the overlay.
- Browse no longer applies a second chrome/status-bar top inset.
- Loading, error, and empty states render below the chrome.
- Existing phone hero metadata and menu-order tests remain green.
- Phone release assembly succeeds.
- On a dedicated phone emulator when available: scroll Recommended,
  Browse, and Collections; confirm content disappears at the chrome boundary,
  the fixed chrome remains usable, and the profile popup does not move or
  expose scrolling content beneath its anchor.

Physical devices are excluded unless separately authorized.
