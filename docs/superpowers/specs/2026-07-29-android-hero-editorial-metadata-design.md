# Android Hero Editorial Metadata Design

**Date:** 2026-07-29
**Status:** Approved
**Repository:** `silo-android`
**Clients:** Android phone and Android TV

## Goal

Align Android hero metadata around the title being browsed rather than the
technical properties of one file. Preserve the phone's established editorial
presentation, add the equivalent active Android TV Home metadata, and remove
technical tokens from the Android TV detail hero.

## Product contract

Movie and series browse heroes use this priority:

1. Year
2. Runtime
3. IMDb rating
4. One or two genres as space permits
5. Content rating

Episode browse heroes use:

1. Season and episode identity
2. Runtime
3. IMDb rating when valid
4. Content rating

The episode title remains a title element. Episode heroes do not repeat
series-level genres in the compact metadata line.

Detail heroes use editorial facts:

- Movies and episodes: year or air date, runtime, IMDb rating, one or two
  genres, and content rating
- Series: year, season count, IMDb rating, one or two genres, and content
  rating
- Optional director or studio/network text may remain outside the compact
  facts row where the current phone layout already supports it

Technical metadata does not appear in Home or detail heroes:

- Resolution or 4K
- HDR or Dolby Vision
- Audio codec, layout, or Atmos
- Subtitle or CC availability

Playback/version selectors and existing technical-detail formatting remain.
No server or API changes are required.

## Active surfaces

The active phone browse hero is `FeaturedCarousel`, currently used by the
Libraries featured/recommended surface. The active Android TV Home/browse hero
is the Skyline `TvFocusMarquee`; the older `TvHomeHeroCarousel` and
`TvFeaturedCarousel` are not active targets.

The shared TV marquee policy applies wherever the active Skyline component is
used, including Home and library Browse. This avoids diverging metadata rules
between two presentations of the same component.

## Architecture

### Shared browse metadata policy

Move the platform-neutral metadata selection and validation rules into a
small pure model in `shared`. Both the phone `FeaturedCarousel` and TV
`TvFocusMarquee` consume the same ordered editorial tokens and map them to
their native chip/text presentation.

The policy accepts a `SectionItem` and an explicit `maxGenres` value. It owns:

- Episode versus non-episode token selection
- Catalog-runtime-first fallback behavior
- IMDb validation and stable one-decimal formatting
- Genre trimming, blank removal, first-seen deduplication, and limiting
- Content-rating trimming and uppercase normalization
- Omission of all `overlaySummary` technical fields

Runtime resolution is:

1. Use a positive catalog `runtime` value as minutes.
2. Otherwise use a positive finite `durationSeconds`, round seconds to
   minutes, and omit a rounded-zero result.
3. Otherwise omit runtime.

IMDb ratings must be finite, greater than zero, and no greater than ten.

### Phone browse width policy

The phone uses one genre below 600 dp available screen width and up to two
genres at 600 dp or wider. `FeaturedCarousel` already reads
`LocalConfiguration.screenWidthDp`; it passes the resulting genre allowance
to the pure metadata policy.

This deterministic breakpoint avoids measurement feedback loops and ensures a
second genre cannot unexpectedly displace content rating on compact phones.
The existing two-line `FlowRow`, chip visuals, title, overview, and actions
remain unchanged.

### Phone detail normalization

Phone detail already excludes technical media properties. Its metadata
helpers are hardened rather than redesigned:

- Only finite IMDb ratings in the supported range are shown
- TMDB is not presented as IMDb when IMDb is absent
- Rating formatting is locale-stable
- Genres are trimmed, deduplicated, and capped at two
- Content ratings are trimmed and uppercased
- Empty source/facts rows are omitted

The current director/studio/network presentation and active playback
selectors remain unchanged.

### Android TV Home and Browse

The active `TvFocusMarquee` consumes the shared browse policy. It keeps its
single-line, overscan-safe layout, native separators, title/logo, synopsis,
artwork, and detail enrichment.

Movies and series use up to two genres. Episodes keep season/episode identity,
episode title, runtime, and valid rating without adding genres. Content rating
remains the classification badge. Technical `overlaySummary` values remain
excluded.

The quiet enrichment line, including air date and cast, is not changed by the
Android work; this design only removes hard-coded technical hero facts.

### Android TV detail

`TvDetailMetadata.factsLine` becomes editorial-only. It no longer accepts
preferred-quality or selected-file parameters solely to build resolution,
HDR, audio, and CC chips. Hero-only quality helpers are removed.

`TvItemDetailScreen` stops passing file-selection state into hero metadata.
Version selection, audio/subtitle selection, playback preparation,
`TvDetailVersionSelection`, `TvPlaybackSelectorRow`, and media-info formatting
remain unchanged.

The generic hero token renderer may retain dormant chip support if other
callers still need it; no dead public API is preserved solely for this change.

## Error and lifecycle behavior

- Missing metadata shortens the row; no `Unknown`, zero, blank chip, or empty
  separator is shown.
- Long metadata follows the existing platform truncation/wrapping behavior.
- Metadata calculation is pure and does not introduce loading, coroutine,
  focus, or navigation state.
- TV focus, phone carousel timing, artwork loading, playback routing, and
  selector state remain unchanged.
- No ADB, device configuration, storage, auth, or server behavior is involved.

## Expected files

Shared policy:

- `shared/src/commonMain/kotlin/org/siloserver/silo/model/section/`
- `shared/src/commonTest/kotlin/org/siloserver/silo/model/section/`

Phone:

- `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt`
- `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt`
- `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt`
- Existing Home metadata tests plus a focused phone detail metadata test

TV:

- `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt`
- `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt`
- `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt`
- Existing marquee and detail metadata tests

Inactive legacy hero components are not modified unless compilation proves
they share a required contract.

## Test design

Shared policy fixtures cover:

- Complete movie metadata and exact priority
- Episode identity and episode-specific omissions
- Catalog runtime precedence and duration fallback
- Exact-hour and mixed-hour runtime formatting
- Missing, zero, negative, non-finite, and rounded-zero values
- Valid and invalid IMDb ratings
- Compact one-genre and wide two-genre limits
- Blank, padded, duplicate, and long genres
- Trimmed/uppercased content rating
- Explicit proof that technical overlay values never enter editorial tokens

Phone tests cover:

- The 600 dp genre breakpoint
- Stable detail rating and content-rating normalization
- Two-genre detail cap
- Existing carousel composition and actions remaining intact

TV tests cover:

- Active marquee movie/series/episode token ordering
- Content-rating badge preservation
- Detail hero omission of resolution, HDR/Dolby Vision, audio, and CC
- Version selectors and technical formatters remaining unchanged

Verification includes focused shared/phone/TV tests, complete module unit
tests, phone and TV release compilation, and later visual smoke checks at one
compact phone width, one wide phone width, and one TV overscan-safe width.

## Non-goals

- Server, API, or database changes
- Activating legacy carousel components
- Wiring currently inaccessible technical dialogs
- Removing technical information from selectors or playback details
- Pixel-identical phone and TV layouts
- Changing cast rails, navigation, focus, playback, or artwork behavior
