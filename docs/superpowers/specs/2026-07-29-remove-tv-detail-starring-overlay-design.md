# Remove Android TV Detail Starring Overlay

**Date:** 2026-07-29
**Status:** Approved

## Purpose

Remove the floating `Starring …` credit from the upper-right of the Android TV
item-detail hero. The credit duplicates the full cast and crew section lower on
the same page, competes with backdrop artwork, and remains difficult to make
consistently legible across arbitrary imagery.

The resulting hero should preserve a clearer hierarchy:

1. title and primary metadata;
2. synopsis and optional translation;
3. movie-only `Directed by …` credit;
4. facts and actions.

## Scope

- Stop deriving and passing `starringText` into `TvDetailHero`.
- Remove the `starringText` parameter and its upper-right text overlay from
  `TvDetailHero`.
- Remove the now-unused `TvDetailMetadata.starringText` helper and its focused
  unit coverage.
- Preserve `TvCastCrewSection` on the TV detail page as the complete cast and
  crew presentation.
- Preserve the movie-only `Directed by …` credit on Android TV and phone.
- Preserve the existing title-detail content, actions, focus behavior, hero
  gradients, synopsis, translation, and fact tokens.

## Client Impact

This is intentionally an Android TV-only visual simplification. Android phone
does not have the floating upper-right starring overlay, so no phone production
UI changes are required. Both clients retain their existing cast and crew
content and the shared movie director-credit behavior.

No server, API, model, persistence, navigation, playback, or Apple-client
changes are included.

## Behavior

For every supported TV item type, the upper-right hero area is left to the
backdrop artwork. Cast data remains available by scrolling to the existing cast
and crew section. Empty or absent cast data behaves exactly as before outside
the removed overlay.

No replacement shadow, glyph halo, localized vignette, panel, or inline
`Starring …` row is introduced. This avoids adding visual machinery for
duplicated metadata.

## Implementation Boundary

The change should remain within the TV detail presentation and its focused
metadata tests:

- `androidTvApp/.../detail/TvItemDetailScreen.kt`
- `androidTvApp/.../detail/TvDetailHero.kt`
- `androidTvApp/.../detail/TvDetailMetadata.kt`
- `androidTvApp/.../detail/TvDetailMetadataTest.kt`

If source-level tests directly assert the removed parameter or call site, update
them narrowly. Do not refactor unrelated hero layout or metadata formatting.

## Verification

- Focused TV detail metadata/source tests confirm the starring helper and hero
  wiring are gone while director-credit ordering remains intact.
- Android TV unit tests pass.
- Android TV debug and release compilation succeeds.
- A TV/emulator detail-page smoke check confirms:
  - no floating upper-right starring credit;
  - cast and crew remains available below;
  - `Directed by …` remains between synopsis/translation and facts for movies;
  - hero focus, actions, and scrolling are unchanged.

## Acceptance Criteria

- No `Starring …` overlay appears in the Android TV detail hero.
- The existing cast and crew section is unchanged and remains reachable.
- The movie director credit remains unchanged on phone and TV.
- No substitute contrast treatment or actor-credit placement is added.
- No phone, server, protocol, playback, or persistence behavior changes.
