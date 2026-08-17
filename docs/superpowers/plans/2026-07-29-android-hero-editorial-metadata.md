# Android Hero Editorial Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the active Android phone and TV browse/detail heroes one normalized editorial metadata contract while removing resolution, HDR, audio, and subtitle tokens from the TV detail hero without changing playback selectors or technical-detail formatting.

**Architecture:** Add a small pure `shared` policy that turns `SectionItem` browse data into ordered, validated editorial fields; the phone Libraries `FeaturedCarousel` and active TV Skyline `TvFocusMarquee` adapt that policy to their existing chip/text layouts. Harden phone detail metadata locally, make TV detail metadata editorial-only, and leave navigation, focus, artwork, enrichment, playback, selectors, inactive carousels, and server contracts untouched.

**Tech Stack:** Kotlin 2.1 Multiplatform, Java 21, kotlinx.serialization models, Jetpack Compose Material3 for phone, Compose for TV Material3, `kotlin.test`/JUnit, Gradle Android/KMP test and release tasks.

## Global Constraints

- Repository: `silo-android`; implementation branch: `feat/android-hero-editorial-metadata`; base: `origin/main` at `1788c40b`.
- Implement the approved design in `docs/superpowers/specs/2026-07-29-android-hero-editorial-metadata-design.md`.
- The active phone browse hero is `FeaturedCarousel`, used by the Libraries featured/recommended surface; do not describe or wire it as a new `HomeScreen` carousel.
- The active TV Home/library Browse hero is Skyline `TvFocusMarquee`; do not modify or activate `TvHomeHeroCarousel` or `TvFeaturedCarousel`.
- Browse order for movies/series is year, runtime, IMDb rating, one or two genres, then content rating.
- Browse order for episodes is season/episode identity, runtime, valid IMDb rating, then content rating; the episode title stays a title element and browse episodes do not add series-level genres.
- Phone browse uses one genre below 600 dp available screen width and up to two genres at 600 dp or wider.
- Positive catalog `runtime` minutes win; otherwise derive minutes from positive finite `durationSeconds`, round to the nearest minute, and omit rounded zero.
- IMDb ratings must be finite, greater than zero, and no greater than ten; formatting is locale-stable with one decimal.
- Genres are trimmed, blank-filtered, first-seen deduplicated, and capped by the caller-provided limit.
- Content ratings are trimmed and uppercased; blank values are omitted.
- No Home or detail hero may show resolution/4K, HDR/Dolby Vision, audio codec/layout/Atmos, or subtitle/CC availability.
- Playback/version selectors and existing technical-detail formatting remain available and unchanged.
- Do not change server, API, database, serialization field names, or `SectionItem`/`ItemDetail` payload shape.
- Do not wire the currently inaccessible `TvMediaInfoDialog`; that is a non-goal.
- Do not change cast rails, TV focus, phone carousel timing, artwork, navigation, playback routing, or enrichment lifecycle.
- Do not use ADB, install an APK, alter device configuration, storage, authentication, or server state unless the user separately authorizes it.
- Each implementation task ends with focused green tests and its own commit. Do not squash task commits before independent review.

---

## File map and decomposition

- Create `shared/src/commonMain/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadata.kt`: the sole platform-neutral browse selection, validation, normalization, and formatting policy.
- Create `shared/src/commonTest/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadataTest.kt`: exhaustive policy fixtures for order, runtime fallback, rating validity, genres, classification, episodes, and technical exclusion.
- Modify `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt`: map shared fields to existing phone chip kinds and own the deterministic 600 dp genre-count decision.
- Modify `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt`: pass the screen-width genre allowance through the active Libraries hero without changing composition, visuals, timing, overview, or actions.
- Modify `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadataTest.kt`: verify the phone adapter and exact 599/600 dp boundary.
- Modify `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt`: normalize phone detail IMDb, genres, content rating, and empty-row behavior while retaining editorial source text and selectors.
- Create `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/detail/PhoneDetailHeroMetadataTest.kt`: focused phone detail normalization coverage.
- Modify `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt`: adapt the shared policy to the active Skyline title/meta/badge contract and preserve enrichment.
- Modify `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModelTest.kt`: cover movie, series, and episode Skyline ordering with two genres only for non-episodes.
- Modify `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt`: make detail source/fact fields normalized and editorial-only; remove file-quality dependencies.
- Modify `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt`: stop deriving/passing file selection solely for hero metadata.
- Modify `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt`: remove hero token branches that become unreferenced after technical chips disappear.
- Modify `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadataTest.kt`: replace quality-token expectations with editorial ordering, normalization, and explicit technical omission.
- Verify without modifying `TvDetailVersionSelection.kt`, `TvPlaybackSelectorRow.kt`, `TvPlaybackFormatting.kt`, `TvMediaInfoDialog.kt`, their focused tests, both inactive TV carousel files, shared serialization models, and server/network code.

---

### Task 1: Create the shared pure browse metadata policy

**Files:**
- Create: `shared/src/commonMain/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadata.kt`
- Create: `shared/src/commonTest/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadataTest.kt`

**Interfaces:**
- Consumes: `SectionItem.runtime: Int?`, `durationSeconds: Double?`, `ratingImdb: Double?`, `genres: List<String>`, `contentRating: String?`, `year: Int`, `type: String`, `seasonNumber: Int?`, and `episodeNumber: Int?`.
- Produces: `data class BrowseHeroMetadata(val leadingToken: String?, val runtimeToken: String?, val imdbRatingToken: String?, val genres: List<String>, val contentRating: String?)`.
- Produces: `fun BrowseHeroMetadata.orderedTokens(): List<String>`.
- Produces: `fun SectionItem.toBrowseHeroMetadata(maxGenres: Int): BrowseHeroMetadata`.
- Does not read `SectionItem.overlaySummary`, position/progress, artwork, network, studio, synopsis, or user state.

- [ ] **Step 1: Write the complete shared policy test file**

Create `BrowseHeroMetadataTest.kt` with concrete fixtures:

```kotlin
package org.siloserver.silo.model.section

import org.siloserver.silo.model.catalog.OverlaySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BrowseHeroMetadataTest {
    private fun movie(
        runtime: Int? = 116,
        durationSeconds: Double? = 60.0,
        rating: Double? = 7.9,
        genres: List<String> = listOf("Science Fiction", "Drama"),
        contentRating: String? = " pg-13 ",
    ) = SectionItem(
        contentId = "movie-1",
        type = "movie",
        title = "Arrival",
        year = 2016,
        runtime = runtime,
        durationSeconds = durationSeconds,
        ratingImdb = rating,
        genres = genres,
        contentRating = contentRating,
        overlaySummary = OverlaySummary(
            resolution = "2160p",
            hdr = "Dolby Vision",
            audio = "TrueHD Atmos",
        ),
    )

    @Test
    fun completeMovieUsesExactEditorialPriorityAndNeverUsesTechnicalOverlay() {
        val metadata = movie().toBrowseHeroMetadata(maxGenres = 2)

        assertEquals(
            listOf("2016", "1h 56m", "7.9", "Science Fiction", "Drama"),
            metadata.orderedTokens(),
        )
        assertEquals("PG-13", metadata.contentRating)
    }

    @Test
    fun episodeUsesIdentityRuntimeAndRatingWithoutGenres() {
        val metadata = SectionItem(
            contentId = "episode-1",
            type = "episode",
            title = "Long, Long Time",
            seriesTitle = "The Last of Us",
            seasonNumber = 1,
            episodeNumber = 3,
            runtime = 76,
            ratingImdb = 8.6,
            genres = listOf("Drama", "Horror"),
            contentRating = " tv-ma ",
        ).toBrowseHeroMetadata(maxGenres = 2)

        assertEquals(listOf("S1 E3", "1h 16m", "8.6"), metadata.orderedTokens())
        assertEquals(emptyList(), metadata.genres)
        assertEquals("TV-MA", metadata.contentRating)
    }

    @Test
    fun partialEpisodeIdentityOmitsOnlyMissingPart() {
        val seasonOnly = SectionItem(
            contentId = "season-only",
            type = "episode",
            title = "Episode",
            seasonNumber = 2,
        ).toBrowseHeroMetadata(maxGenres = 2)
        val episodeOnly = SectionItem(
            contentId = "episode-only",
            type = "episode",
            title = "Episode",
            episodeNumber = 7,
        ).toBrowseHeroMetadata(maxGenres = 2)

        assertEquals("Season 2", seasonOnly.leadingToken)
        assertEquals("Episode 7", episodeOnly.leadingToken)
    }

    @Test
    fun positiveCatalogRuntimeWinsOverDurationFallback() {
        assertEquals(
            "2h 5m",
            movie(runtime = 125, durationSeconds = 60.0)
                .toBrowseHeroMetadata(maxGenres = 1)
                .runtimeToken,
        )
    }

    @Test
    fun invalidCatalogRuntimeFallsBackToRoundedPositiveDuration() {
        assertEquals(
            "1h 56m",
            movie(runtime = 0, durationSeconds = 6_960.0)
                .toBrowseHeroMetadata(maxGenres = 1)
                .runtimeToken,
        )
        assertEquals(
            "2h",
            movie(runtime = -2, durationSeconds = 7_200.0)
                .toBrowseHeroMetadata(maxGenres = 1)
                .runtimeToken,
        )
    }

    @Test
    fun missingNonFiniteNonPositiveAndRoundedZeroRuntimeAreOmitted() {
        listOf(
            null,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -60.0,
            29.0,
        ).forEach { duration ->
            assertNull(
                movie(runtime = null, durationSeconds = duration)
                    .toBrowseHeroMetadata(maxGenres = 1)
                    .runtimeToken,
            )
        }
    }

    @Test
    fun ratingUsesStableOneDecimalAndRejectsUnsupportedValues() {
        assertEquals(
            "8.0",
            movie(rating = 8.04).toBrowseHeroMetadata(maxGenres = 1).imdbRatingToken,
        )
        assertEquals(
            "8.1",
            movie(rating = 8.05).toBrowseHeroMetadata(maxGenres = 1).imdbRatingToken,
        )
        listOf(
            null,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0,
            10.1,
        ).forEach { rating ->
            assertNull(
                movie(rating = rating)
                    .toBrowseHeroMetadata(maxGenres = 1)
                    .imdbRatingToken,
            )
        }
        assertEquals(
            "10.0",
            movie(rating = 10.0).toBrowseHeroMetadata(maxGenres = 1).imdbRatingToken,
        )
    }

    @Test
    fun genresAreTrimmedDeduplicatedAndLimitedInFirstSeenOrder() {
        val genres = listOf(" Drama ", "", "Drama", "Comedy", " comedy ", "Thriller")

        assertEquals(
            listOf("Drama"),
            movie(genres = genres).toBrowseHeroMetadata(maxGenres = 1).genres,
        )
        assertEquals(
            listOf("Drama", "Comedy"),
            movie(genres = genres).toBrowseHeroMetadata(maxGenres = 2).genres,
        )
    }

    @Test
    fun longGenreIsPreservedForExistingPlatformTruncation() {
        val longGenre = "Documentary About Science Technology Engineering and Mathematics"

        assertEquals(
            listOf(longGenre),
            movie(genres = listOf(" $longGenre "))
                .toBrowseHeroMetadata(maxGenres = 1)
                .genres,
        )
    }

    @Test
    fun genreLimitZeroIsSupportedAndNegativeLimitIsRejected() {
        assertEquals(
            emptyList(),
            movie().toBrowseHeroMetadata(maxGenres = 0).genres,
        )
        assertFailsWith<IllegalArgumentException> {
            movie().toBrowseHeroMetadata(maxGenres = -1)
        }
    }

    @Test
    fun contentRatingIsTrimmedUppercasedAndBlankIsOmitted() {
        assertEquals(
            "PG-13",
            movie(contentRating = " pg-13 ").toBrowseHeroMetadata(maxGenres = 1).contentRating,
        )
        assertNull(
            movie(contentRating = "  ").toBrowseHeroMetadata(maxGenres = 1).contentRating,
        )
    }
}
```

- [ ] **Step 2: Run the shared test to verify RED**

Run:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests "org.siloserver.silo.model.section.BrowseHeroMetadataTest" \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: compilation fails because `BrowseHeroMetadata`, `orderedTokens`, and `toBrowseHeroMetadata` do not exist.

- [ ] **Step 3: Implement the minimal pure common policy**

Create `BrowseHeroMetadata.kt`:

```kotlin
package org.siloserver.silo.model.section

import kotlin.math.abs
import kotlin.math.roundToInt

data class BrowseHeroMetadata(
    val leadingToken: String?,
    val runtimeToken: String?,
    val imdbRatingToken: String?,
    val genres: List<String>,
    val contentRating: String?,
)

fun BrowseHeroMetadata.orderedTokens(): List<String> = buildList {
    leadingToken?.let(::add)
    runtimeToken?.let(::add)
    imdbRatingToken?.let(::add)
    addAll(genres)
}

fun SectionItem.toBrowseHeroMetadata(maxGenres: Int): BrowseHeroMetadata {
    require(maxGenres >= 0) { "maxGenres must be non-negative" }
    val isEpisode = type.equals("episode", ignoreCase = true)
    return BrowseHeroMetadata(
        leadingToken = if (isEpisode) {
            browseEpisodeToken(seasonNumber, episodeNumber)
        } else {
            year.takeIf { it > 0 }?.toString()
        },
        runtimeToken = browseRuntimeToken(runtime, durationSeconds),
        imdbRatingToken = browseRatingToken(ratingImdb),
        genres = if (isEpisode) {
            emptyList()
        } else {
            genres
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(maxGenres)
        },
        contentRating = contentRating
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase(),
    )
}

private fun browseEpisodeToken(season: Int?, episode: Int?): String? = when {
    season != null && episode != null -> "S$season E$episode"
    season != null -> "Season $season"
    episode != null -> "Episode $episode"
    else -> null
}

private fun browseRuntimeToken(runtimeMinutes: Int?, durationSeconds: Double?): String? {
    runtimeMinutes?.takeIf { it > 0 }?.let { return formatBrowseRuntime(it) }
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val roundedMinutes = (duration / 60.0).roundToInt().takeIf { it > 0 } ?: return null
    return formatBrowseRuntime(roundedMinutes)
}

private fun formatBrowseRuntime(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) "${hours}h" else "${hours}h ${remainder}m"
}

private fun browseRatingToken(rating: Double?): String? {
    val valid = rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 } ?: return null
    val tenths = (valid * 10.0).roundToInt()
    return "${tenths / 10}.${abs(tenths % 10)}"
}
```

- [ ] **Step 4: Run the shared test to verify GREEN**

Run the command from Step 2.

Expected: all `BrowseHeroMetadataTest` cases pass.

- [ ] **Step 5: Run shared serialization regressions**

Run:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests "org.siloserver.silo.model.catalog.MediaSurfaceContractSerializationTest" \
  --tests "org.siloserver.silo.network.HomeRealtimeDecodeTest" \
  --max-workers=2 --no-daemon
```

Expected: both existing suites pass, proving the new pure type did not change serialized payload models.

- [ ] **Step 6: Commit the shared policy**

```bash
git add \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadata.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadataTest.kt
git commit -m "feat(shared): centralize browse hero metadata"
```

---

### Task 2: Adapt the active phone Libraries hero and enforce the 600 dp rule

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadataTest.kt`
- Verify only: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt`

**Interfaces:**
- Consumes: `SectionItem.toBrowseHeroMetadata(maxGenres: Int)` and `BrowseHeroMetadata` from Task 1.
- Produces: `internal fun featuredHeroMaxGenres(screenWidthDp: Int): Int`.
- Produces: `internal fun featuredHeroMetadata(item: SectionItem, maxGenres: Int): List<FeaturedHeroMetadataChip>`.
- Preserves: existing `FeaturedHeroMetadataKind`, two-line `FlowRow`, chip rendering, title/episode eyebrow, overview, Play/More Info actions, pager timing, artwork, and the sole active caller in `LibrariesScreen`.

- [ ] **Step 1: Change phone tests to require the shared adapter and exact breakpoint**

In `FeaturedHeroMetadataTest.kt`, update every `featuredHeroMetadata(item)` call to pass `maxGenres = 1`, update the complete movie fixture to include two genres, and add:

```kotlin
@Test
fun phoneGenreAllowanceChangesExactlyAtSixHundredDp() {
    assertEquals(1, featuredHeroMaxGenres(screenWidthDp = 0))
    assertEquals(1, featuredHeroMaxGenres(screenWidthDp = 599))
    assertEquals(2, featuredHeroMaxGenres(screenWidthDp = 600))
    assertEquals(2, featuredHeroMaxGenres(screenWidthDp = 840))
}

@Test
fun compactAndWidePhoneMapTheSharedGenreLimitWithoutMovingClassification() {
    val item = SectionItem(
        contentId = "movie-width",
        type = "movie",
        title = "Arrival",
        year = 2016,
        runtime = 116,
        ratingImdb = 7.9,
        genres = listOf(" Science Fiction ", "Drama", "Drama"),
        contentRating = " pg-13 ",
    )

    assertEquals(
        listOf("2016", "1h 56m", "7.9", "Science Fiction", "PG-13"),
        featuredHeroMetadata(item, maxGenres = featuredHeroMaxGenres(599)).map { it.label },
    )
    assertEquals(
        listOf("2016", "1h 56m", "7.9", "Science Fiction", "Drama", "PG-13"),
        featuredHeroMetadata(item, maxGenres = featuredHeroMaxGenres(600)).map { it.label },
    )
    assertEquals(
        FeaturedHeroMetadataKind.Classification,
        featuredHeroMetadata(item, maxGenres = 2).last().kind,
    )
}

@Test
fun phoneEpisodeKeepsGenresOutEvenAtWideAllowance() {
    val chips = featuredHeroMetadata(
        item = SectionItem(
            contentId = "episode-wide",
            type = "episode",
            title = "Long, Long Time",
            seasonNumber = 1,
            episodeNumber = 3,
            runtime = 76,
            ratingImdb = 8.6,
            genres = listOf("Drama", "Horror"),
            contentRating = "TV-MA",
        ),
        maxGenres = 2,
    )

    assertEquals(listOf("S1 E3", "1h 16m", "8.6", "TV-MA"), chips.map { it.label })
}
```

Also change the complete movie test’s expected compact labels to one genre and keep the existing technical `OverlaySummary` fixture so the adapter proves it remains editorial-only.

- [ ] **Step 2: Run the focused phone test to verify RED**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests "org.siloserver.silo.android.ui.screens.home.FeaturedHeroMetadataTest" \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: compilation fails because the new signature and `featuredHeroMaxGenres` do not exist.

- [ ] **Step 3: Replace duplicated phone policy with a shared-policy mapper**

Rewrite `featuredHeroMetadata` and delete its local rating/runtime/episode formatting helpers:

```kotlin
package org.siloserver.silo.android.ui.screens.home

import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.section.toBrowseHeroMetadata

internal enum class FeaturedHeroMetadataKind {
    Plain,
    Rating,
    Classification,
}

internal data class FeaturedHeroMetadataChip(
    val label: String,
    val kind: FeaturedHeroMetadataKind = FeaturedHeroMetadataKind.Plain,
)

internal fun featuredHeroMaxGenres(screenWidthDp: Int): Int =
    if (screenWidthDp >= 600) 2 else 1

internal fun featuredHeroMetadata(
    item: SectionItem,
    maxGenres: Int,
): List<FeaturedHeroMetadataChip> {
    val metadata = item.toBrowseHeroMetadata(maxGenres)
    return buildList {
        metadata.leadingToken?.let { add(FeaturedHeroMetadataChip(it)) }
        metadata.runtimeToken?.let { add(FeaturedHeroMetadataChip(it)) }
        metadata.imdbRatingToken?.let {
            add(FeaturedHeroMetadataChip(it, FeaturedHeroMetadataKind.Rating))
        }
        metadata.genres.forEach { add(FeaturedHeroMetadataChip(it)) }
        metadata.contentRating?.let {
            add(FeaturedHeroMetadataChip(it, FeaturedHeroMetadataKind.Classification))
        }
    }
}
```

- [ ] **Step 4: Thread the deterministic allowance through the active carousel**

In `FeaturedCarousel`:

1. After reading `LocalConfiguration.current`, compute:

```kotlin
val maxHeroGenres = featuredHeroMaxGenres(configuration.screenWidthDp)
```

2. Add `maxGenres: Int` to the private `FeaturedCard` and `FeaturedCardContent` parameters.
3. Pass `maxHeroGenres` from the pager’s `FeaturedCard` call, then from `FeaturedCard` to `FeaturedCardContent`.
4. Replace:

```kotlin
val chips = remember(item) { featuredHeroMetadata(item) }
```

with:

```kotlin
val chips = remember(item, maxGenres) {
    featuredHeroMetadata(item = item, maxGenres = maxGenres)
}
```

Do not change `FlowRow(maxLines = 2)`, `MetadataChip`, title/logo rendering, overview, action buttons, pager dimensions, or auto-advance.

- [ ] **Step 5: Run the phone adapter test to verify GREEN**

Run the command from Step 2.

Expected: all existing runtime/invalid-value tests and new 599/600 dp tests pass.

- [ ] **Step 6: Compile the active phone UI and verify its caller remains Libraries-only**

Run:

```bash
./gradlew :androidApp:compileDebugKotlinAndroid --max-workers=2 --no-daemon
rg -n "FeaturedCarousel\\(" \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens
```

Expected:

- Kotlin compilation passes.
- The production call remains `LibrariesScreen.kt`; no new `HomeScreen` call appears.
- `TvHomeHeroCarousel.kt` and `TvFeaturedCarousel.kt` are untouched.

- [ ] **Step 7: Commit the phone browse adapter**

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadataTest.kt
git commit -m "fix(phone): adapt featured hero editorial metadata"
```

---

### Task 3: Normalize phone detail editorial metadata

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/detail/PhoneDetailHeroMetadataTest.kt`
- Verify only: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt`
- Verify only: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/SeriesDetailContent.kt`
- Verify only: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MediaSelectors.kt`

**Interfaces:**
- Consumes: `ItemDetail` editorial fields only.
- Produces: `HeroMetadata.movieEyebrow(detail): String?` using only valid IMDb.
- Produces: `HeroMetadata.contentRating(detail): String?`.
- Produces: normalized `movieSourceTokens`, `seriesSourceTokens`, `movieFactsLine`, and `seriesFactsLine`.
- Preserves: director credit, studio/network source tokens, action stack, version/audio/subtitle selectors, and existing detail call sites.

- [ ] **Step 1: Write focused phone detail normalization tests**

Create `PhoneDetailHeroMetadataTest.kt`:

```kotlin
package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.model.catalog.ItemDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneDetailHeroMetadataTest {
    private fun detail(
        type: String = "movie",
        ratingImdb: Double? = 7.9,
        ratingTmdb: Double? = 8.8,
        genres: List<String> = listOf(" Drama ", "", "Drama", "Science Fiction", "Thriller"),
        contentRating: String? = " pg-13 ",
    ) = ItemDetail(
        contentId = "detail-1",
        type = type,
        title = "Arrival",
        year = 2016,
        runtime = 116,
        ratingImdb = ratingImdb,
        ratingTmdb = ratingTmdb,
        genres = genres,
        contentRating = contentRating,
        studios = listOf(" Paramount "),
        networks = listOf(" HBO "),
    )

    @Test
    fun validImdbUsesLocaleStableLabelAndFacts() {
        val detail = detail(ratingImdb = 8.05)

        assertEquals("IMDb 8.1", HeroMetadata.movieEyebrow(detail))
        assertEquals(listOf("Drama · Science Fiction", "IMDb 8.1"), HeroMetadata.movieFactsLine(detail))
    }

    @Test
    fun absentImdbDoesNotRelabelTmdbAsImdb() {
        val detail = detail(ratingImdb = null, ratingTmdb = 8.8)

        assertNull(HeroMetadata.movieEyebrow(detail))
        assertEquals(listOf("Drama · Science Fiction"), HeroMetadata.movieFactsLine(detail))
    }

    @Test
    fun invalidImdbValuesAreOmittedFromEyebrowAndFacts() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0,
            10.1,
        ).forEach { invalid ->
            val detail = detail(ratingImdb = invalid)
            assertNull(HeroMetadata.movieEyebrow(detail))
            assertEquals(listOf("Drama · Science Fiction"), HeroMetadata.movieFactsLine(detail))
        }
    }

    @Test
    fun detailGenresAreTrimmedDeduplicatedAndCappedAtTwo() {
        assertEquals(
            listOf("Drama · Science Fiction", "IMDb 7.9"),
            HeroMetadata.seriesFactsLine(detail(type = "series")),
        )
    }

    @Test
    fun detailContentRatingIsTrimmedUppercasedAndBlankSafe() {
        assertEquals("PG-13", HeroMetadata.contentRating(detail()))
        assertNull(HeroMetadata.contentRating(detail(contentRating = "  ")))
        assertNull(HeroMetadata.contentRating(detail(contentRating = null)))
    }

    @Test
    fun sourceTokensTrimEditorialStudioAndNetworkAndOmitEmptyValues() {
        assertEquals(
            listOf("2016", "1h 56m", "Paramount"),
            HeroMetadata.movieSourceTokens(detail()),
        )
        assertEquals(
            listOf("2016", "HBO"),
            HeroMetadata.seriesSourceTokens(detail(type = "series")),
        )
    }
}
```

- [ ] **Step 2: Run the phone detail test to verify RED**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests "org.siloserver.silo.android.ui.screens.detail.PhoneDetailHeroMetadataTest" \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: compilation fails because `HeroMetadata.contentRating` does not exist; existing invalid-rating, TMDB fallback, genre count, whitespace, and rounding behavior also disagree.

- [ ] **Step 3: Add normalized phone detail helpers**

In `HeroMetadata`, implement:

```kotlin
fun movieEyebrow(detail: ItemDetail): String? =
    validImdb(detail.ratingImdb)?.let { "IMDb ${formatOneDecimal(it)}" }

fun seriesEyebrow(detail: ItemDetail): String? = movieEyebrow(detail)

fun contentRating(detail: ItemDetail): String? =
    detail.contentRating
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.uppercase(Locale.US)

fun movieSourceTokens(detail: ItemDetail): List<String> = buildList {
    if (detail.year > 0) add(detail.year.toString())
    if (detail.runtime > 0) add(formatRuntime(detail.runtime))
    detail.studios
        .firstNotNullOfOrNull { it.trim().takeIf(String::isNotEmpty) }
        ?.let(::add)
}

fun seriesSourceTokens(detail: ItemDetail): List<String> = buildList {
    if (detail.year > 0) add(detail.year.toString())
    detail.seasonCount?.takeIf { it > 0 }?.let {
        add("$it Season${if (it > 1) "s" else ""}")
    }
    detail.networks
        .firstNotNullOfOrNull { it.trim().takeIf(String::isNotEmpty) }
        ?.let(::add)
}

fun movieFactsLine(detail: ItemDetail): List<String> = detailFactsLine(detail)

fun seriesFactsLine(detail: ItemDetail): List<String> = detailFactsLine(detail)

private fun detailFactsLine(detail: ItemDetail): List<String> = buildList {
    normalizedGenres(detail.genres).takeIf { it.isNotEmpty() }?.let {
        add(it.joinToString(" · "))
    }
    validImdb(detail.ratingImdb)?.let { add("IMDb ${formatOneDecimal(it)}") }
}

private fun normalizedGenres(genres: List<String>): List<String> =
    genres
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(2)

private fun validImdb(rating: Double?): Double? =
    rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }

private fun formatOneDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value)
```

Add `import java.util.Locale`. Keep `episodeEyebrow` and `formatRuntime` behavior unchanged.

- [ ] **Step 4: Normalize the rendered classification and empty-row gate**

At the top of `DetailHero`, compute:

```kotlin
val normalizedContentRating = HeroMetadata.contentRating(detail)
```

Replace:

```kotlin
if (sourceTokens.isNotEmpty() || detail.contentRating != null) {
    SourceRow(
        tokens = sourceTokens,
        ratingChip = detail.contentRating,
    )
}
```

with:

```kotlin
if (sourceTokens.isNotEmpty() || normalizedContentRating != null) {
    SourceRow(
        tokens = sourceTokens,
        ratingChip = normalizedContentRating,
    )
}
```

Do not change the source/facts row composables, title, overview, director placement, action stack, or selectors.

- [ ] **Step 5: Run phone detail and existing detail action tests**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests "org.siloserver.silo.android.ui.screens.detail.PhoneDetailHeroMetadataTest" \
  --tests "org.siloserver.silo.android.ui.screens.detail.DetailPlayLabelTest" \
  --tests "org.siloserver.silo.android.ui.screens.detail.MobileDetailActionsSourceTest" \
  --tests "org.siloserver.silo.android.ui.screens.detail.PhoneDirectorCreditSourceTest" \
  --max-workers=2 --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 6: Compile phone detail and prove technical selectors remain**

Run:

```bash
./gradlew :androidApp:compileDebugKotlinAndroid --max-workers=2 --no-daemon
rg -n "TrackSelectorRow|formatVersionValueLabel|formatAudioValueLabel|formatSubtitleValueLabel" \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MediaSelectors.kt
```

Expected: compilation passes and Video, Audio, and Subtitles selector paths still exist.

- [ ] **Step 7: Commit phone detail normalization**

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/detail/PhoneDetailHeroMetadataTest.kt
git commit -m "fix(phone): normalize detail hero metadata"
```

---

### Task 4: Adapt the active TV Skyline marquee

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModelTest.kt`
- Verify only: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarquee.kt`
- Verify only: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt`
- Do not modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvHomeHeroCarousel.kt`
- Do not modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFeaturedCarousel.kt`

**Interfaces:**
- Consumes: `SectionItem.toBrowseHeroMetadata(maxGenres = 2)` from Task 1.
- Produces: unchanged `TvMarqueeContent` fields and identity.
- Preserves: episode series title as marquee title, episode title in `metaParts`, content rating as the only badge, single-line truncation, synopsis, quiet air-date/cast enrichment, artwork upgrade, cache, and crossfade behavior.

- [ ] **Step 1: Extend active marquee tests for shared ordering**

Update the movie fixture in `TvFocusMarqueeModelTest` to use:

```kotlin
genres = listOf(" Science Fiction ", "Drama", "Drama"),
contentRating = " pg-13 ",
```

and expect:

```kotlin
assertEquals(listOf("PG-13"), content.badges)
assertEquals(
    listOf("2016", "1h 56m", "7.9", "Science Fiction", "Drama"),
    content.metaParts,
)
```

Keep the technical `OverlaySummary` fixture. Add:

```kotlin
@Test
fun seriesHeroUsesYearRuntimeRatingAndTwoGenres() {
    val content = TvMarqueeContent.from(
        item = SectionItem(
            contentId = "series-1",
            type = "series",
            title = "Severance",
            year = 2022,
            runtime = 50,
            ratingImdb = 8.7,
            genres = listOf("Drama", "Mystery", "Thriller"),
            contentRating = "TV-MA",
        ),
        rowTitle = "Popular",
    )

    assertEquals(
        listOf("2022", "50 min", "8.7", "Drama", "Mystery"),
        content.metaParts,
    )
    assertEquals(listOf("TV-MA"), content.badges)
}

@Test
fun episodeKeepsTitlePlacementAndNeverAddsGenres() {
    val content = TvMarqueeContent.from(
        item = SectionItem(
            contentId = "episode-genres",
            type = "episode",
            title = "Long, Long Time",
            seriesTitle = "The Last of Us",
            seasonNumber = 1,
            episodeNumber = 3,
            runtime = 76,
            ratingImdb = 8.6,
            genres = listOf("Drama", "Horror"),
            contentRating = " tv-ma ",
        ),
        rowTitle = "Continue Watching",
    )

    assertEquals("The Last of Us", content.title)
    assertEquals(
        listOf("S1 E3", "Long, Long Time", "1h 16m", "8.6"),
        content.metaParts,
    )
    assertEquals(listOf("TV-MA"), content.badges)
}
```

Retain existing missing/invalid/runtime preference tests; they protect the adapter contract after local helpers are removed.

- [ ] **Step 2: Run the active marquee test to verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests "org.siloserver.silo.tv.ui.components.TvFocusMarqueeModelTest" \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: movie/series two-genre and padded-classification expectations fail.

- [ ] **Step 3: Replace local selection/validation with the shared policy**

Import:

```kotlin
import org.siloserver.silo.model.section.toBrowseHeroMetadata
```

Inside `TvMarqueeContent.from`, replace local metadata and badge construction with:

```kotlin
val isEpisode = item.type.equals("episode", ignoreCase = true)
val editorial = item.toBrowseHeroMetadata(maxGenres = 2)
val meta = if (isEpisode) {
    buildList {
        editorial.leadingToken?.let(::add)
        item.title.takeIf(String::isNotBlank)?.let(::add)
        editorial.runtimeToken?.let(::add)
        editorial.imdbRatingToken?.let(::add)
    }
} else {
    editorial.orderedTokens()
}
val badges = editorial.contentRating?.let(::listOf).orEmpty()
```

Also import:

```kotlin
import org.siloserver.silo.model.section.orderedTokens
```

Delete the now-unused local `episodeToken`, `timeLeftText`, `lengthText`, `runtimeText`, `ratingToken`, `validImdbRating`, and `formatRating` functions plus unused `Locale`/`roundToInt` imports. Do not modify `TvMarqueeEnrichment`, `withEnrichment`, state, timing, or renderer.

- [ ] **Step 4: Run marquee model and enrichment tests to verify GREEN**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests "org.siloserver.silo.tv.ui.components.TvFocusMarqueeModelTest" \
  --tests "org.siloserver.silo.tv.ui.components.TvFocusMarqueeEnrichmentTest" \
  --max-workers=2 --no-daemon
```

Expected: all tests pass; enrichment still keeps air date/cast separate and episode artwork upgrades preserve identity.

- [ ] **Step 5: Compile TV and prove Skyline is the active caller**

Run:

```bash
./gradlew :androidTvApp:compileDebugKotlinAndroid --max-workers=2 --no-daemon
rg -n "TvSkylineSectionFeed|TvFocusMarquee\\(" \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt
git diff --exit-code -- \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvHomeHeroCarousel.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFeaturedCarousel.kt
```

Expected: compilation passes, Home still routes through Skyline, and both inactive carousel files have no diff.

- [ ] **Step 6: Commit the active TV browse adapter**

```bash
git add \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModelTest.kt
git commit -m "fix(tv): adapt skyline hero editorial metadata"
```

---

### Task 5: Remove technical tokens from TV detail while preserving selectors

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadataTest.kt`
- Verify only: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailVersionSelection.kt`
- Verify only: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvPlaybackSelectorRow.kt`
- Verify only: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvPlaybackFormatting.kt`
- Verify only: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvMediaInfoDialog.kt`

**Interfaces:**
- Changes: `TvDetailMetadata.factsLine(detail: ItemDetail, zone: ZoneId = ZoneId.systemDefault()): List<TvHeroFactToken>`.
- Removes: hero-only `preferredQuality` and `selectedFileId` inputs and quality helper functions.
- Produces: normalized content rating and one/two genres in `sourceTokens`; editorial date/year, runtime/season count, and valid IMDb in `factsLine`.
- Preserves: `selectTvDetailDisplayVersion`, all version/audio/subtitle selector state, player launch selection, `TvPlaybackSelectorRow`, `TvPlaybackFormatting`, and `TvMediaInfoDialog` formatting.

- [ ] **Step 1: Replace TV detail quality tests with editorial-only tests**

Retain the existing episode date/zone and audiobook tests. Delete the three tests named:

- `factsLineUsesPreferredQualityForVersionBadges`
- `factsLineUsesSelectedFileIdForVersionBadges`
- `factsLineNamesDolbyVisionFromVideoTrackMetadata`

Add:

```kotlin
@Test
fun movieDetailUsesNormalizedEditorialMetadataAndOmitsAllTechnicalTokens() {
    val detail = ItemDetail(
        contentId = "movie-detail",
        type = "movie",
        title = "Arrival",
        year = 2016,
        runtime = 116,
        ratingImdb = 7.9,
        genres = listOf(" Science Fiction ", "", "Drama", "Drama", "Thriller"),
        contentRating = " pg-13 ",
        versions = listOf(
            FileVersion(
                fileId = 2160,
                resolution = "2160p",
                hdr = true,
                videoTracks = listOf(
                    VideoTrack(codec = "hevc", dolbyVision = "Profile 8", hdr = true),
                ),
                audioTracks = listOf(
                    AudioTrack(channelLayout = "Atmos", channels = 8, isDefault = true),
                ),
                subtitleTracks = listOf(SubtitleTrack(language = "en")),
            ),
        ),
    )

    assertEquals(
        listOf("Movie", "Science Fiction", "Drama"),
        TvDetailMetadata.sourceTokens(detail),
    )
    assertEquals("PG-13", TvDetailMetadata.ratingChip(detail))
    assertEquals(
        listOf(
            TvHeroFactToken.TextToken("2016"),
            TvHeroFactToken.TextToken("1h 56m"),
            TvHeroFactToken.TextToken("★ 7.9"),
        ),
        TvDetailMetadata.factsLine(detail),
    )
}

@Test
fun episodeDetailAllowsTwoNormalizedGenresAndKeepsEditorialFactOrder() {
    val detail = ItemDetail(
        contentId = "episode-detail",
        type = "episode",
        title = "Long, Long Time",
        seasonNumber = 1,
        episodeNumber = 3,
        airDate = "2026-03-30T00:00:00Z",
        runtime = 76,
        ratingImdb = 8.6,
        genres = listOf("Drama", " Horror ", "Drama"),
        contentRating = " tv-ma ",
    )

    assertEquals(
        listOf("Season 1 · Episode 3", "Drama", "Horror"),
        TvDetailMetadata.sourceTokens(detail),
    )
    assertEquals("TV-MA", TvDetailMetadata.ratingChip(detail))
    assertEquals(
        listOf(
            TvHeroFactToken.TextToken("Mar 30, 2026"),
            TvHeroFactToken.TextToken("1h 16m"),
            TvHeroFactToken.TextToken("★ 8.6"),
        ),
        TvDetailMetadata.factsLine(detail, zone = ZoneId.of("UTC")),
    )
}

@Test
fun invalidDetailRatingsAndBlankClassificationsAreOmitted() {
    listOf(
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        0.0,
        -1.0,
        10.1,
    ).forEach { invalid ->
        val detail = ItemDetail(
            contentId = "invalid-$invalid",
            type = "movie",
            title = "Invalid",
            ratingImdb = invalid,
            contentRating = "  ",
        )

        assertEquals(emptyList(), TvDetailMetadata.factsLine(detail))
        assertEquals(null, TvDetailMetadata.ratingChip(detail))
    }
}
```

Keep imports for `FileVersion`, `AudioTrack`, `SubtitleTrack`, and `VideoTrack` because the omission fixture needs real technical metadata.

- [ ] **Step 2: Run TV detail metadata tests to verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvDetailMetadataTest" \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: source-token normalization and invalid-rating assertions fail, and technical `Chip` tokens remain in movie facts.

- [ ] **Step 3: Normalize TV detail source and classification fields**

In `TvDetailMetadata`, add:

```kotlin
private fun normalizedGenres(detail: ItemDetail): List<String> =
    detail.genres
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(2)
```

Use `normalizedGenres(detail)` in every video source-token branch:

```kotlin
fun sourceTokens(detail: ItemDetail): List<String> = when {
    detail.type.equals("episode", ignoreCase = true) -> buildList {
        episodeNumberLabel(detail)?.let(::add)
        addAll(normalizedGenres(detail))
    }
    detail.type.equals("series", ignoreCase = true) -> buildList {
        add("TV Show")
        addAll(normalizedGenres(detail))
    }
    detail.type.equals("season", ignoreCase = true) -> buildList {
        detail.episodeCount?.takeIf { it > 0 }?.let {
            add("$it Episode${if (it == 1) "" else "s"}")
        }
        addAll(normalizedGenres(detail))
    }
    isAudiobookItemType(detail.type) -> listOfNotNull(
        "Audiobook",
        detail.audiobook?.publisher,
        detail.audiobook?.narratorNames?.let { "Narrated by $it" },
    )
    else -> buildList {
        add(typeLabel(detail))
        addAll(normalizedGenres(detail))
    }
}

fun ratingChip(detail: ItemDetail): String? =
    detail.contentRating
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.uppercase()
```

- [ ] **Step 4: Make TV detail facts editorial-only**

Change `factsLine` to:

```kotlin
fun factsLine(
    detail: ItemDetail,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TvHeroFactToken> {
    val tokens = mutableListOf<TvHeroFactToken>()
    if (detail.type.equals("episode", ignoreCase = true)) {
        abbreviatedDate(detail.airDate ?: detail.releaseDate, zone)?.let {
            tokens += TvHeroFactToken.TextToken(it)
        }
    } else if (detail.year > 0) {
        tokens += TvHeroFactToken.TextToken(detail.year.toString())
    }
    when {
        detail.type.equals("series", ignoreCase = true) ->
            detail.seasonCount?.takeIf { it > 0 }?.let {
                tokens += TvHeroFactToken.TextToken("$it Season${if (it == 1) "" else "s"}")
            }
        else ->
            runtimeLabel(detail.runtime)?.let { tokens += TvHeroFactToken.TextToken(it) }
    }
    detail.ratingImdb
        ?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
        ?.let { tokens += TvHeroFactToken.TextToken("★ ${formatOneDecimal(it)}") }
    return tokens
}
```

Delete `qualityTokens`, `preferredVersion`, `resolutionLabel`, `primaryAudioLabel`, and `hasSubtitles`, then remove the unused `FileVersion` import. Keep `abbreviatedDate`, date parsing, episode identity, type labels, audiobook metadata, and runtime behavior.

- [ ] **Step 5: Remove file-selection state from the hero call only**

In `TvItemDetailScreen`:

1. Delete the hero-only variable:

```kotlin
val heroSelectedFileId = if (detail.type == "series" || detail.type == "season") {
    state.selectedNextUpFileId
} else {
    state.selectedFileId
}
```

2. Replace:

```kotlin
factsLine = TvDetailMetadata.factsLine(
    detail = detail,
    preferredQuality = state.preferredQuality,
    selectedFileId = heroSelectedFileId,
),
```

with:

```kotlin
factsLine = TvDetailMetadata.factsLine(detail),
```

Do not change the selector-local `selectedVersion`, `selectorSelectedFileId`, `state.preferredQuality`, `playFileId`, or any `HeroActionRow` logic later in the file.

- [ ] **Step 6: Remove unreferenced technical hero token rendering**

After Step 4, search:

```bash
rg -n "TvHeroFactToken\\.(Rating|Chip)|QualityChip\\(" \
  androidTvApp/src/androidMain \
  androidTvApp/src/androidUnitTest
```

Expected before cleanup: only definitions/renderer branches remain. In `TvDetailHero.kt`:

- Remove `TvHeroFactToken.Rating`.
- Remove `TvHeroFactToken.Chip`.
- Remove the `Rating` and `Chip` branches from `FactsRow`.
- Remove `QualityChip`.
- Remove imports used only by those branches, including `Icons.Filled.CheckCircle` and `SuccessGreen` if `rg` confirms no remaining use in this file.
- Update the token KDoc to state that the facts row contains editorial `TextToken` values.

Retain the sealed type and `TextToken` to minimize the public shape of `TvDetailHero`.

- [ ] **Step 7: Run TV detail tests and selector/technical preservation suites**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvDetailMetadataTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvDetailVersionSelectionTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvPlaybackFormattingTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvMediaInfoFormattingTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvTrackSelectionPersistenceTest" \
  --max-workers=2 --no-daemon
```

Expected: all suites pass. The first test proves technical fields no longer become hero tokens; the remaining suites prove version selection and technical formatting still work.

- [ ] **Step 8: Compile TV and inspect preservation boundaries**

Run:

```bash
./gradlew :androidTvApp:compileDebugKotlinAndroid --max-workers=2 --no-daemon
rg -n "selectTvDetailDisplayVersion|TvPlaybackSelectorRow|onSelectVersion|onSelectAudioTrack|onSelectSubtitleTrack" \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvPlaybackSelectorRow.kt
rg -n "resolution|HDR|codec|channelLayout|subtitle" \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvMediaInfoDialog.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvPlaybackFormatting.kt
```

Expected: compilation passes, selectors remain wired, and technical detail formatters remain present outside the hero.

- [ ] **Step 9: Commit TV detail cleanup**

```bash
git add \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadataTest.kt
git commit -m "fix(tv): remove technical detail hero tokens"
```

---

### Task 6: Run focused, full, and release verification gates

**Files:**
- Verify only: all files changed in Tasks 1–5
- Do not create or commit: APK copies, logs, screenshots, Gradle caches, IDE metadata, or device state

**Interfaces:**
- Consumes: the five implementation commits.
- Produces: reproducible command evidence that shared policy, phone, TV, selectors, and release variants are green.

- [ ] **Step 1: Confirm exact diff scope and formatting**

Run:

```bash
git status --short
git diff --check origin/main...HEAD
git diff --stat origin/main...HEAD
git diff --name-only origin/main...HEAD
```

Expected changed implementation paths are limited to the approved shared policy, active phone hero/detail, active Skyline model, TV detail metadata/screen/renderer, their tests, the approved spec commit, and this plan. No server/API/database, inactive carousel, generated, signing, SDK, or local-tool files appear.

- [ ] **Step 2: Run all focused metadata suites with module-specific filters**

Run these exact commands:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests "org.siloserver.silo.model.section.BrowseHeroMetadataTest" \
  --rerun-tasks --max-workers=2 --no-daemon

./gradlew :androidApp:testDebugUnitTest \
  --tests "org.siloserver.silo.android.ui.screens.home.FeaturedHeroMetadataTest" \
  --tests "org.siloserver.silo.android.ui.screens.detail.PhoneDetailHeroMetadataTest" \
  --rerun-tasks --max-workers=2 --no-daemon

./gradlew :androidTvApp:testDebugUnitTest \
  --tests "org.siloserver.silo.tv.ui.components.TvFocusMarqueeModelTest" \
  --tests "org.siloserver.silo.tv.ui.components.TvFocusMarqueeEnrichmentTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvDetailMetadataTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvDetailVersionSelectionTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvPlaybackFormattingTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvMediaInfoFormattingTest" \
  --tests "org.siloserver.silo.tv.ui.screens.detail.TvTrackSelectionPersistenceTest" \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: every selected test passes with zero failures.

- [ ] **Step 3: Run complete module unit tests**

Run:

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --max-workers=2 --no-daemon
```

Expected: all shared, phone, and TV unit tests pass.

- [ ] **Step 4: Compile debug phone and TV applications**

Run:

```bash
./gradlew \
  :androidApp:assembleDebug \
  :androidTvApp:assembleDebug \
  --max-workers=2 --no-daemon
```

Expected: both debug APKs assemble successfully. Do not install either APK.

- [ ] **Step 5: Compile both release applications**

Run:

```bash
./gradlew \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2 --no-daemon
```

Expected: both release variants assemble successfully. The flag permits local compilation with debug signing; do not distribute, copy, install, or commit the resulting APKs.

- [ ] **Step 6: Record the no-ADB visual smoke gate**

Do not run `adb`, `installDebug`, `installRelease`, APK drag-and-drop, device configuration commands, or authenticated server mutations.

If the user separately authorizes an already-provisioned emulator/device install, visually check:

- Phone at 599 dp: one genre, content rating remains present, existing two-line wrapping/actions are intact.
- Phone at 600 dp or wider: up to two genres, content rating remains present.
- Phone movie/episode/series detail: normalized rating, two genre cap, uppercase content rating, no technical hero tokens, selectors still visible.
- TV Skyline Home and library Browse: movie/series year → runtime → rating → two genres, episode identity → title → runtime → rating, classification badge, no technical badge.
- TV detail at an overscan-safe width: editorial facts only; selector row still exposes version/audio/subtitle choices.

Without that separate authorization, report visual smoke as “not run: device/APK installation not authorized,” not as a pass or failure. Compile and unit-test evidence remain the automated acceptance evidence.

- [ ] **Step 7: Commit only if verification required a source/test correction**

If a gate exposed a real implementation defect, fix it with a focused regression test, rerun the failed gate and all focused suites, then commit only the correction:

```bash
git add \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadata.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadataTest.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadataTest.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/detail/PhoneDetailHeroMetadataTest.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModelTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadataTest.kt
git commit -m "fix(android): address hero metadata verification"
```

If no correction was required, do not create an empty verification commit.

---

### Task 7: Independent review, final audit, and separate pull request

**Files:**
- Review: `git diff origin/main...HEAD`
- Review: every changed production/test/doc file
- Remote output: one new PR from `feat/android-hero-editorial-metadata` to `main`

**Interfaces:**
- Consumes: green verification evidence and unsquashed task commits.
- Produces: independent spec/conformance review, resolved material findings, and a separate PR for this approved feature.

- [ ] **Step 1: Request an independent read-only review**

Use the `superpowers:requesting-code-review` skill and give a fresh reviewer this exact brief:

```text
Review origin/main...HEAD for the approved Android hero editorial metadata design.
Check shared pure browse ordering/validation, the exact phone 599/600 dp genre rule,
the active Libraries FeaturedCarousel wiring, phone detail IMDb/genre/classification
normalization, active TV Skyline wiring, TV detail technical-token removal, and
preservation of version/audio/subtitle selectors and technical formatters.
Confirm inactive TvHomeHeroCarousel/TvFeaturedCarousel and server/API models are
untouched. Report only actionable findings with file/line evidence. Do not edit.
```

Expected: a review report with either no findings or concrete severity-ranked findings.

- [ ] **Step 2: Resolve every material review finding test-first**

For each P0/P1/P2 correctness or spec-conformance finding:

1. Add a focused failing test in the owning module.
2. Run only that test and capture the expected failure.
3. Make the smallest production correction.
4. Rerun that test plus all Task 6 focused suites.
5. Commit the source and test together:

```bash
git add \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadata.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadataTest.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadataTest.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/detail/PhoneDetailHeroMetadataTest.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModelTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadataTest.kt
git commit -m "fix(android): address editorial hero review"
```

Do not implement unrelated cleanup, inactive carousel changes, dialog wiring, server work, or layout redesign suggested during review.

- [ ] **Step 3: Perform final spec coverage and boundary audit**

Run:

```bash
git diff --check origin/main...HEAD
rg -n "overlaySummary|resolution|DOLBY VISION|Dolby Vision|ATMOS|Atmos|\\bCC\\b" \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/section/BrowseHeroMetadata.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt
git diff --exit-code origin/main...HEAD -- \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvHomeHeroCarousel.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFeaturedCarousel.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/section/SectionModels.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/catalog/CatalogModels.kt
```

Expected:

- `git diff --check` is silent.
- The technical-term search has no production hero-policy match; technical terms may remain only in explicit negative-test fixtures.
- Inactive carousels and shared payload models have no diff.

- [ ] **Step 4: Re-run the complete final gate after review fixes**

Run:

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2 --no-daemon
```

Expected: all tests and both release assemblies pass.

- [ ] **Step 5: Push the feature branch**

Run:

```bash
git status --short --branch
git log --oneline --decorate origin/main..HEAD
git push -u origin feat/android-hero-editorial-metadata
```

Expected: the worktree is clean before push and the remote feature branch contains the approved spec plus separate implementation commits.

- [ ] **Step 6: Create a separate PR**

First confirm there is no existing PR for this head:

```bash
gh pr list \
  --state all \
  --head feat/android-hero-editorial-metadata \
  --json number,title,state,url
```

If the list is empty, create the separate PR:

```bash
gh pr create \
  --base main \
  --head feat/android-hero-editorial-metadata \
  --title "fix(android): align hero editorial metadata" \
  --body "$(printf '%s\n' \
    '## Summary' \
    '- centralize validated browse hero metadata in shared Kotlin' \
    '- apply the 600 dp phone genre rule to the active Libraries featured hero and normalize phone detail facts' \
    '- apply the shared policy to active TV Skyline and remove technical tokens from the TV detail hero' \
    '- preserve version/audio/subtitle selectors and technical-detail formatting' \
    '' \
    '## Verification' \
    '- shared, phone, and TV unit tests' \
    '- phone and TV release assembly' \
    '- independent code review' \
    '- visual smoke not run unless separately authorized; no ADB or APK installation used')"
```

If the list is not empty, stop and report the existing PR URL rather than creating a duplicate. Do not append this work to an unrelated existing PR.

- [ ] **Step 7: Report the final handoff**

Report:

- PR URL and branch head SHA.
- The exact focused/full/release commands that passed.
- Independent review result and any review-fix commits.
- Visual smoke status, explicitly stating whether it was not run because device installation was not authorized.
- Confirmation that no ADB/device install, server/API change, inactive carousel change, technical-dialog wiring, or selector removal occurred.

## Plan Self-Review

- **Spec coverage:** Tasks 1–5 cover every approved shared, phone, active Skyline, and TV-detail requirement; Tasks 6–7 cover focused/full/release gates, authorization-safe visual smoke, independent review, and a separate PR. No approved requirement is left without an owning task.
- **Placeholder scan:** The plan contains no deferred implementation markers or unspecified code/test steps.
- **Type consistency:** `BrowseHeroMetadata`, `orderedTokens`, and `toBrowseHeroMetadata(maxGenres)` keep the same signatures across shared, phone, and TV tasks; the revised `TvDetailMetadata.factsLine(detail, zone)` signature matches its tests and `TvItemDetailScreen` call.
- **Scope audit:** Only the plan file was created while planning. Implementation explicitly excludes inactive TV carousels, payload/API models, device installs, inaccessible-dialog wiring, and selector/technical formatter removal.
