# Phone Director Credit and Review Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PR #129's movie-only “Directed by …” hero credit to Android phone with one shared phone/TV formatting rule, add the missing PR #128/#129 regressions, and deterministically fix the unrelated hosted purger-test race.

**Architecture:** A pure `movieDirectorCredit(ItemDetail): String?` presentation helper will live in `android-shared` and serve both Android clients. Phone and TV keep their platform-specific Compose rendering but use the shared string. Runtime work is characterization coverage only, while the purger correction is confined to explicit test-harness gates and does not change production semantics.

**Tech Stack:** Kotlin 2.1, Jetpack Compose, Kotlin coroutines and `CompletableDeferred`, Kotlin test/JUnit, Gradle, repository shell supply-chain checks.

## Global Constraints

- Show the credit on movie detail pages only.
- Place it directly below the synopsis and optional description translation, and directly above the facts row.
- Match only crew jobs whose trimmed value equals `Director`, case-insensitively.
- Trim names, remove blanks, preserve first occurrence while de-duplicating, and show at most three names.
- Do not change server APIs, catalog models, navigation, cast/crew sections, or production purge behavior.
- Do not widen timeouts or add retries to conceal the hosted purger-test race.
- Preserve PR #128's existing positive-runtime preference and duration fallback.
- Update PR #129, but do not merge it.

---

### Task 1: Shared Director-Credit Rule and TV Migration

**Files:**
- Create: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/ui/DirectorCredit.kt`
- Create: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/ui/DirectorCreditTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDirectorCreditSourceTest.kt`

**Interfaces:**
- Consumes: `org.siloserver.silo.model.catalog.ItemDetail` and its ordered `crew: List<CrewMember>`.
- Produces: public pure function `fun movieDirectorCredit(detail: ItemDetail): String?`.
- Produces: TV hero wiring through `directorText = movieDirectorCredit(detail)`.

- [ ] **Step 1: Write the failing shared formatting tests**

Create `DirectorCreditTest.kt` with concrete movie, non-movie, exact-job, cleanup, de-duplication, and cap cases:

```kotlin
package org.siloserver.silo.common.ui

import org.siloserver.silo.model.catalog.CrewMember
import org.siloserver.silo.model.catalog.ItemDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectorCreditTest {
    @Test
    fun movieCreditMatchesExactDirectorJobAndCleansNames() {
        val detail = ItemDetail(
            contentId = "movie-1",
            type = "MoViE",
            title = "Movie",
            crew = listOf(
                CrewMember(name = " Alice ", job = " director "),
                CrewMember(name = "Camera", job = "Director of Photography"),
                CrewMember(name = "", job = "Director"),
                CrewMember(name = "Alice", job = "DIRECTOR"),
                CrewMember(name = "Bob", job = "Director"),
            ),
        )

        assertEquals("Directed by Alice, Bob", movieDirectorCredit(detail))
    }

    @Test
    fun movieCreditKeepsServerOrderAndCapsAtThreeNames() {
        val detail = ItemDetail(
            contentId = "movie-2",
            type = "movie",
            title = "Movie",
            crew = listOf("One", "Two", "Three", "Four").map {
                CrewMember(name = it, job = "Director")
            },
        )

        assertEquals("Directed by One, Two, Three", movieDirectorCredit(detail))
    }

    @Test
    fun movieCreditIsAbsentForNonMoviesOrMissingDirectors() {
        assertNull(
            movieDirectorCredit(
                ItemDetail(
                    contentId = "episode-1",
                    type = "episode",
                    title = "Episode",
                    crew = listOf(CrewMember(name = "Alice", job = "Director")),
                ),
            ),
        )
        assertNull(
            movieDirectorCredit(
                ItemDetail(
                    contentId = "movie-3",
                    type = "movie",
                    title = "Movie",
                    crew = listOf(CrewMember(name = "Camera", job = "Cinematographer")),
                ),
            ),
        )
    }
}
```

- [ ] **Step 2: Run the shared test and verify RED**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests 'org.siloserver.silo.common.ui.DirectorCreditTest' \
  --no-daemon
```

Expected: compilation fails because `movieDirectorCredit` does not exist.

- [ ] **Step 3: Implement the minimal shared rule**

Create `DirectorCredit.kt`:

```kotlin
package org.siloserver.silo.common.ui

import org.siloserver.silo.model.catalog.ItemDetail

fun movieDirectorCredit(detail: ItemDetail): String? {
    if (!detail.type.equals("movie", ignoreCase = true)) return null
    val names = detail.crew
        .asSequence()
        .filter { it.job?.trim().equals("Director", ignoreCase = true) }
        .map { it.name.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(3)
        .toList()
    return names.takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "Directed by ", separator = ", ")
}
```

- [ ] **Step 4: Write the failing TV wiring/placement test**

Create `TvDirectorCreditSourceTest.kt`:

```kotlin
package org.siloserver.silo.tv.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvDirectorCreditSourceTest {
    private val hero = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt",
    ).readText()
    private val screen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()

    @Test
    fun tvMovieHeroUsesSharedDirectorCredit() {
        assertTrue(screen.contains("directorText = movieDirectorCredit(detail)"))
    }

    @Test
    fun tvCreditStaysBetweenTranslationAndFacts() {
        val translation = hero.indexOf("translation?.invoke()")
        val director = hero.indexOf("directorText?.takeIf")
        val facts = hero.indexOf("if (factsLine.isNotEmpty())", startIndex = director)
        assertTrue(translation >= 0 && translation < director && director < facts)
    }
}
```

- [ ] **Step 5: Run the TV source test and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.tv.ui.screens.detail.TvDirectorCreditSourceTest' \
  --no-daemon
```

Expected: `tvMovieHeroUsesSharedDirectorCredit` fails because PR #129 still calls `TvDetailMetadata.directorText`.

- [ ] **Step 6: Migrate TV to the shared helper**

Delete `TvDetailMetadata.directorText`. Import `org.siloserver.silo.common.ui.movieDirectorCredit` in `TvItemDetailScreen.kt` and replace:

```kotlin
directorText = TvDetailMetadata.directorText(detail),
```

with:

```kotlin
directorText = movieDirectorCredit(detail),
```

- [ ] **Step 7: Run shared and TV tests and verify GREEN**

Run:

```bash
./gradlew \
  :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*DirectorCreditTest' \
  --no-daemon
```

Expected: all director formatting and TV wiring tests pass.

- [ ] **Step 8: Commit Task 1**

```bash
git add \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/ui/DirectorCredit.kt \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/ui/DirectorCreditTest.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDirectorCreditSourceTest.kt
git commit -m "refactor(detail): share movie director credit"
```

---

### Task 2: Phone Movie-Hero Director Credit

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/detail/PhoneDirectorCreditSourceTest.kt`

**Interfaces:**
- Consumes: Task 1's `fun movieDirectorCredit(detail: ItemDetail): String?`.
- Produces: optional `directorText: String? = null` parameter on `DetailHero`.
- Produces: movie-only phone wiring `directorText = movieDirectorCredit(detail)`.

- [ ] **Step 1: Write the failing phone wiring/placement tests**

Create `PhoneDirectorCreditSourceTest.kt`:

```kotlin
package org.siloserver.silo.android.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PhoneDirectorCreditSourceTest {
    private val hero = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt",
    ).readText()
    private val movie = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt",
    ).readText()

    @Test
    fun phoneMovieHeroUsesSharedDirectorCredit() {
        assertTrue(hero.contains("directorText: String? = null"))
        assertTrue(movie.contains("directorText = movieDirectorCredit(detail)"))
    }

    @Test
    fun phoneCreditStaysBetweenTranslationAndFacts() {
        val translation = hero.indexOf("translation?.invoke()")
        val director = hero.indexOf("directorText?.takeIf")
        val facts = hero.indexOf("if (factsLine.isNotEmpty())", startIndex = director)
        assertTrue(translation >= 0 && translation < director && director < facts)
    }
}
```

- [ ] **Step 2: Run the phone test and verify RED**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests 'org.siloserver.silo.android.ui.screens.detail.PhoneDirectorCreditSourceTest' \
  --no-daemon
```

Expected: both tests fail because phone has no director hero parameter, rendering, or shared-helper call.

- [ ] **Step 3: Add the minimal phone hero rendering**

In `DetailSharedComponents.kt`, add this parameter immediately before `translation`:

```kotlin
directorText: String? = null,
```

Immediately after `translation?.invoke()` and before the facts condition, render:

```kotlin
directorText?.takeIf { it.isNotBlank() }?.let { line ->
    Text(
        text = line,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = DetailTertiaryText,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}
```

In `MovieDetailContent.kt`, import:

```kotlin
import org.siloserver.silo.common.ui.movieDirectorCredit
```

and pass:

```kotlin
directorText = movieDirectorCredit(detail),
```

to `DetailHero`. Do not change the `SeriesDetailContent` call; the optional default keeps all non-movie paths unchanged.

- [ ] **Step 4: Run the phone and shared director tests and verify GREEN**

Run:

```bash
./gradlew \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  --tests '*DirectorCreditTest' \
  --no-daemon
```

Expected: shared formatting and phone wiring/placement tests pass.

- [ ] **Step 5: Commit Task 2**

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/detail/PhoneDirectorCreditSourceTest.kt
git commit -m "feat(phone): show movie director credit"
```

---

### Task 3: PR #128 Runtime Preference and Fallback Coverage

**Files:**
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadataTest.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModelTest.kt`

**Interfaces:**
- Consumes: existing `featuredHeroMetadata(SectionItem): List<FeaturedHeroMetadataChip>`.
- Consumes: existing `TvMarqueeContent.from(SectionItem, String): TvMarqueeContent`.
- Produces: characterization coverage only; no production interface changes.

- [ ] **Step 1: Add explicit phone preference and fallback tests**

Append to `FeaturedHeroMetadataTest`:

```kotlin
@Test
fun catalogRuntimeWinsOverPlaybackDurationOnPhone() {
    val chips = featuredHeroMetadata(
        SectionItem(
            contentId = "movie-runtime",
            type = "movie",
            title = "Movie",
            runtime = 125,
            durationSeconds = 60.0,
        ),
    )

    assertEquals(listOf("2h 5m"), chips.map { it.label })
}

@Test
fun invalidCatalogRuntimeFallsBackToPlaybackDurationOnPhone() {
    val chips = featuredHeroMetadata(
        SectionItem(
            contentId = "movie-runtime-fallback",
            type = "movie",
            title = "Movie",
            runtime = 0,
            durationSeconds = 6_960.0,
        ),
    )

    assertEquals(listOf("1h 56m"), chips.map { it.label })
}
```

- [ ] **Step 2: Add explicit TV preference and fallback tests**

Append to `TvFocusMarqueeModelTest`:

```kotlin
@Test
fun catalogRuntimeWinsOverPlaybackDurationOnTv() {
    val content = TvMarqueeContent.from(
        item = SectionItem(
            contentId = "movie-runtime",
            type = "movie",
            title = "Movie",
            runtime = 125,
            durationSeconds = 60.0,
        ),
        rowTitle = "Row",
    )

    assertEquals(listOf("2h 5m"), content.metaParts)
}

@Test
fun invalidCatalogRuntimeFallsBackToPlaybackDurationOnTv() {
    val content = TvMarqueeContent.from(
        item = SectionItem(
            contentId = "movie-runtime-fallback",
            type = "movie",
            title = "Movie",
            runtime = 0,
            durationSeconds = 6_960.0,
        ),
        rowTitle = "Row",
    )

    assertEquals(listOf("1h 56m"), content.metaParts)
}
```

- [ ] **Step 3: Prove the characterization tests detect regression**

Temporarily replace the catalog-runtime branch in both production metadata builders with duration-only selection, without staging the mutation. Run:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*FeaturedHeroMetadataTest' \
  --tests '*TvFocusMarqueeModelTest' \
  --no-daemon
```

Expected: both `catalogRuntimeWins...` tests fail with `1m` instead of `2h 5m`. Restore the two production files exactly with:

```bash
git restore \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt
```

- [ ] **Step 4: Run the runtime tests against real production code**

Run:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*FeaturedHeroMetadataTest' \
  --tests '*TvFocusMarqueeModelTest' \
  --no-daemon
```

Expected: all phone and TV hero-runtime tests pass.

- [ ] **Step 5: Commit Task 3**

```bash
git add \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadataTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModelTest.kt
git commit -m "test(home): cover hero runtime preference"
```

---

### Task 4: Deterministic Purger Second-Pass Test Harness

**Files:**
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/downloads/OrphanedServerDataPurgerTest.kt`

**Interfaces:**
- Consumes: existing `OrphanedServerDataPurger.start(): Job`.
- Produces: explicit test-only `secondPurgeStarted` and `allowSecondPurge` gates.
- Does not modify `OrphanedServerDataPurger` or any production source.

- [ ] **Step 1: Preserve the concrete RED evidence**

Record the hosted assertion already observed:

```text
OrphanedServerDataPurgerTest.removal during startup scan triggers a second purge
removed server was unexpectedly part of the startup orphan snapshot
OrphanedServerDataPurgerTest.kt:410
```

Run the exact test once before editing:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests 'org.siloserver.silo.common.downloads.OrphanedServerDataPurgerTest.removal during startup scan triggers a second purge' \
  --max-workers=2 --rerun-tasks --no-daemon
```

Expected: it may pass locally because the defect is an ordering race; the hosted failure is the RED evidence. Do not broaden production scope to force reproduction.

- [ ] **Step 2: Add deterministic second-pass deletion gates**

In the test, create:

```kotlin
val secondPurgeStarted = CompletableDeferred<Unit>()
val allowSecondPurge = CompletableDeferred<Unit>()
```

Change the `purgeRows` branch for `serverId` so the gate occurs before deletion:

```kotlin
purgeRows = { orphanId ->
    if (orphanId == "preexisting-orphan") {
        initialPurgeStarted.complete(Unit)
        finishInitialPurge.await()
    }
    if (orphanId == serverId) {
        secondPurgeStarted.complete(Unit)
        allowSecondPurge.await()
    }
    db.serverPurgeDao().deleteAllRowsForServer(orphanId)
    if (orphanId == "preexisting-orphan") {
        initialRowsPurged.complete(Unit)
    }
    if (orphanId == serverId) {
        rowsPurged.complete(Unit)
    }
},
```

After `initialRowsPurged.await()`, wait for the second pass before asserting:

```kotlin
secondPurgeStarted.await()
assertTrue(observer.isActive, "purge observer stopped: $observerFailure")
assertNull(db.downloadDao().get("preexisting-orphan", "p1", 11))
assertTrue(
    db.downloadDao().get(serverId, "p1", 10) != null,
    "second-pass deletion must remain gated until the snapshot assertion completes",
)

allowSecondPurge.complete(Unit)
rowsPurged.await()
assertNull(db.downloadDao().get(serverId, "p1", 10))
observer.cancel()
observer.join()
```

Remove the old ungated assertion/wait sequence. Do not add sleeps, retries, or timeout changes.

- [ ] **Step 3: Run repeated exact-test verification**

Run the exact command five times:

```bash
for run in 1 2 3 4 5; do
  ./gradlew :android-shared:testDebugUnitTest \
    --tests 'org.siloserver.silo.common.downloads.OrphanedServerDataPurgerTest.removal during startup scan triggers a second purge' \
    --max-workers=2 --rerun-tasks --no-daemon || exit 1
done
```

Expected: 5/5 passes with the observer finishing cleanly.

- [ ] **Step 4: Run the complete purger test class**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests 'org.siloserver.silo.common.downloads.OrphanedServerDataPurgerTest' \
  --max-workers=2 --rerun-tasks --no-daemon
```

Expected: the whole class passes.

- [ ] **Step 5: Commit Task 4**

```bash
git add android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/downloads/OrphanedServerDataPurgerTest.kt
git commit -m "test(downloads): gate purger second-pass assertion"
```

---

### Task 5: Full Verification, Review, and PR #129 Update

**Files:**
- Review: all files changed from `origin/main...HEAD`
- Update remotely: PR #129 branch `RXWatcher:feat/tv-detail-director-credit`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: a clean reviewed PR #129 head with fresh local and hosted evidence.

- [ ] **Step 1: Run all focused regressions together**

```bash
./gradlew \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*DirectorCreditTest' \
  --tests '*FeaturedHeroMetadataTest' \
  --tests '*TvFocusMarqueeModelTest' \
  --tests '*OrphanedServerDataPurgerTest' \
  --max-workers=2 --rerun-tasks --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 2: Run supply-chain policy**

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Expected: both scripts exit zero.

- [ ] **Step 3: Run complete unit and compile gates**

```bash
./gradlew \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidApp:assembleDebug \
  :androidTvApp:assembleDebug \
  --max-workers=2 --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Audit scope and cleanliness**

```bash
git diff --check origin/main...HEAD
git diff --stat origin/main...HEAD
git status --short
git diff --name-only origin/main...HEAD | \
  grep -E '(^server/|proxy|nginx|CatalogModels.kt)' && exit 1 || true
```

Expected: no whitespace errors, no unstaged changes, and no server/proxy/catalog-model files.

- [ ] **Step 5: Request independent review**

Provide the reviewer:

- the approved spec;
- this plan;
- `git diff origin/main...HEAD`;
- focused and full verification results; and
- explicit review questions about exact Director matching, phone/TV placement parity, test-only purger ownership, and accidental production behavior changes.

Resolve every substantive finding with a bounded RED/GREEN fix and rerun the affected focused test. Do not defer confirmed defects.

- [ ] **Step 6: Push the reviewed branch to PR #129**

```bash
git push git@github.com:RXWatcher/silo-android.git \
  HEAD:feat/tv-detail-director-credit
```

Expected: the remote head advances without force-push and PR #129 retains its commit ancestry.

- [ ] **Step 7: Verify PR state and hosted checks**

```bash
gh pr view 129 --repo Silo-Server/silo-android \
  --json state,isDraft,baseRefName,headRefName,headRefOid,mergeable,reviewDecision,url
gh pr checks 129 --repo Silo-Server/silo-android --watch
```

Expected: PR #129 remains open against `main`; hosted checks finish green. Report review requirements separately. Do not merge.
