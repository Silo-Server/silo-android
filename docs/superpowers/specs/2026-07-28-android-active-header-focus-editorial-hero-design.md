# Android Active Header Focus and Editorial Hero Design

**Date:** 2026-07-28
**Status:** Approved for implementation planning
**Scope:** Android TV header focus; Android phone and TV browsing heroes

## Context

External testing of the TV navigation work in PR #126 confirmed that cold
navigation and For You behavior improved. It also exposed two related
presentation defects:

1. Pressing Up from the first content row can focus Search instead of the
   currently active top-menu destination. Pressing Back from the same page
   correctly focuses the active destination.
2. TV browsing heroes prioritize technical stream badges such as resolution,
   HDR, and audio format ahead of editorial information. Phone library heroes
   already avoid those technical fields, but their generic type/year/rating
   chips omit runtime, content classification, and genre.

Issue #78 asks for richer title metadata, but its current wording refers to the
player. This change applies the approved behavior to Android phone and TV
browsing heroes only and does not change either player's or item-detail
surface.

## Goals

- A fresh Up press from the first content row focuses the active top-menu
  destination on Home, each library section, For You, and Calendar.
- Search receives this focus only when Search is the active route.
- Preserve the existing held-Up boundary: a held key stops on the first
  content row and requires a fresh Up press before entering the menu.
- Phone and TV browsing heroes describe the title using consistent editorial
  metadata instead of delivery characteristics or generic media-type labels.
- Keep TV focus behavior inside the existing shared TV shell.
- Keep hero presentation inside the existing TV marquee model and phone
  featured-carousel metadata helper.

## Non-goals

- No server, API, database, or payload changes.
- No player-overlay, playback-settings, or item-detail redesign.
- No phone Home hero: Android phone Home intentionally renders rows without a
  billboard, so phone changes apply only to the existing Library Recommended
  featured carousel.
- No changes to stream selection, transcoding, subtitle behavior, or technical
  metadata availability outside the browsing hero.
- No new user preference or display toggle.
- No Apple-client changes.

## Focus Behavior

The shell remains the single owner of content-to-menu focus transitions. When
the active content feed reports that a fresh Up press has reached its first
row, the shell requests the menu destination derived from the current route:

- Home → Home
- Movies, Series, Music, or Audiobooks → the matching library-type pill
- For You → For You
- Calendar → Calendar
- Search → Search

The request must target the active destination explicitly and complete through
the existing menu focus-request mechanism. It must not depend on Compose
geometric focus search or on the physical proximity of Search to the content
card. The same mapping is used by Back-to-menu behavior so the two entry paths
cannot drift.

Repeated Up events at the first content row remain consumed. Off-screen
previous-row relocation and ordinary row-to-row Up movement are unchanged.
Panel preview, profile menu, Left/Right menu traversal, and Down-to-content
behavior are unchanged.

## Shared Browsing Hero Metadata

The TV browsing marquee stops rendering resolution, HDR, and audio-format
badges. Technical overlay data remains in the model for other consumers but is
not converted into hero badges. The phone featured carousel continues to avoid
technical delivery data.

Both Android clients use the following ordered editorial fields when present:

### Movies and other non-episode titles

1. Release year
2. Runtime
3. IMDb rating
4. Primary genre

Content classification, such as PG-13, remains as the only badge adjacent to
that ordered metadata line.

### Episodes

1. Season and episode token, such as `S2 E7`
2. Episode name
3. Runtime
4. Air date when available from existing enrichment
5. Rating when present

Content classification, such as TV-MA, remains as the only badge adjacent to
that ordered metadata line.

The series name remains the episode hero title, with the episode name in the
metadata line. Missing values are omitted without placeholders or redundant
separators. Existing synopsis, cast enrichment, artwork, cache-first loading,
and crossfade behavior remain unchanged.

The implementation may keep air date and cast on the existing quieter detail
line if the current payload/enrichment boundary does not expose air date early
enough for the primary metadata line. It must not add another detail request or
delay first paint to rearrange those fields.

### TV presentation

TV retains its existing badge-plus-metadata-line layout. Content
classification is its only hero badge; the remaining fields form the ordered
single metadata line. The existing episode series title, episode-name
placement, synopsis, and quieter air-date/cast enrichment remain unchanged.

### Phone presentation

Phone applies the editorial fields to the existing featured carousel used on
Library Recommended pages:

- Remove the generic `Movie` or `Episode` type chip.
- Preserve the existing series eyebrow and title treatment, so an episode
  continues to show the series name and episode name without duplication.
- Present the ordered metadata as compact chips using the existing chip visual
  style.
- Allow chips to wrap onto a second line on narrow phones rather than clipping
  or forcing horizontal scrolling.
- Do not add a hero to phone Home or change item-card overlays.

Phone does not perform detail enrichment in the carousel. Air date remains
absent when it is not carried by the existing section payload; no new request
is introduced to obtain it.

## Data Flow and Boundaries

- `TvMainShell` derives the active root destination from the current route.
- `TvShellFocusState` carries the explicit menu-focus request.
- `TvTopMenuBar` resolves that destination to its existing `FocusRequester`.
- `TvSkylineSectionFeed` retains ownership of row traversal and the held-Up
  boundary, but does not choose a menu target.
- `TvMarqueeContent.from` converts the existing `SectionItem` payload into
  ordered editorial metadata.
- Existing detail enrichment may continue to add air-date/cast information
  without blocking or re-fetching on focus.
- `FeaturedCarousel.metadataChips` converts the same existing `SectionItem`
  fields into ordered phone chips.
- The phone carousel layout owns responsive wrapping without changing its
  paging, play, More Info, or backdrop behavior.

No parallel focus coordinator, marquee data source, or phone detail fetch is
introduced.

## Error and Edge Handling

- If the active route has no top-menu destination, preserve its existing
  route-specific behavior rather than silently selecting Home.
- If a requested library pill is temporarily absent, use the existing safe
  requester fallback and do not crash.
- Invalid, non-finite, zero, negative, blank, or unavailable metadata is
  omitted.
- Valid ratings and runtimes keep the existing formatting and rounding rules.
- Removing technical badges must not create an empty visual row; the row is
  omitted when no editorial badge or metadata value exists.
- Phone wrapping is bounded to two lines of metadata chips; it must not cover
  the carousel actions or change the carousel's page height.

## Verification

Focused tests should cover:

- route-to-menu target mapping for every root destination and Search;
- the held-Up first-row boundary remains unchanged;
- TV movie metadata ordering and omission of resolution/HDR/audio;
- TV episode metadata ordering, series/episode naming, runtime, rating, and
  content-classification handling;
- phone movie and episode chip ordering, removal of the generic type chip,
  runtime/content-classification inclusion, and no technical delivery fields;
- absent, zero, negative, NaN, or infinite ratings and runtimes without empty
  chips or dangling separators.

Regression verification should include the complete Android phone and TV unit
suites, supply-chain checks, and both minified release assemblies. A TV
emulator or external-device smoke should verify:

- Up from the first row lands on the active pill across at least Home, a
  library section, For You, and Calendar;
- Search is selected only on the Search route;
- held Up stops at the first content row;
- representative movie and episode heroes contain editorial metadata and no
  resolution/HDR/audio badges.

A phone emulator smoke should verify that representative movie and episode
featured-carousel pages show the approved chips, wrap without overlapping
actions on a narrow viewport, and retain Play, More Info, paging, and artwork.

## Rollout

Implement this as a focused follow-up on PR #126 while it remains open. The
shared hydration/navigation-performance commits already in PR #126 are its
accepted baseline and remain unchanged; this follow-up does not need to split
or reclassify them. Update both tester APKs after automated verification. Do
not merge or deploy as part of implementation.
