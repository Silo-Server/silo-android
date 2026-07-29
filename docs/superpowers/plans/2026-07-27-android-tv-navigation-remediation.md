# Android TV Navigation Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore predictable Android TV navigation, unify For You saved-list presentation, and remove the duplicate, eager cold-start work that makes first Home traversal sluggish on production-sized servers.

**Architecture:** A shared pure Home hydrator will consume inline aggregate sections and make at most four fallback requests for genuinely unresolved sections. Android TV will use explicit focus policies for recommendation entry and the Home-to-menu boundary, plus a small pure prefetch policy that permits neighbor work only after focus has settled. The For You selector will issue an explicit, repeatable inline-selection request to the existing For You screen; profile-menu saved-list routes remain standalone.

**Tech Stack:** Kotlin Multiplatform, Kotlin coroutines and `kotlinx-coroutines-test`, Compose for TV focus APIs, Ktor `MockEngine`, Room-backed cache ports, Gradle Android unit/release tasks, ADB/gfxinfo for the final Shield smoke.

## Global Constraints

- Do not change server APIs, server configuration, recommendation ranking, Home row composition, authentication, playback, or database schema.
- Preserve Watchlist and Favorites behavior and the existing initial Watchlist focus.
- A repeated/held Up sequence stops on Home's first content row; only a fresh Up press enters the top menu.
- Watchlist and Favorites selected from the For You top-menu selector use the existing inline For You presentation.
- Watchlist and Favorites selected from the profile menu remain standalone utility pages.
- Preserve partial-refresh cache safety: an incomplete network result must not overwrite a complete cached Home.
- Keep item-detail screens network-first; cache-first semantics apply only to speculative marquee enrichment.
- Limit fallback Home hydration and speculative detail fan-out to four concurrent requests.
- Do not hide latency with a longer splash, input suppression, animation tuning, or server-side content caps.
- No production data mutation is permitted during verification.

---

### Task 1: Share bounded, inline-first Home hydration

**Files:**
- Create: `shared/src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeSectionHydrator.kt`
- Create: `shared/src/commonTest/kotlin/org/siloserver/silo/viewmodel/HomeSectionHydratorTest.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt:15-175`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/startup/StartupWarmup.kt:1-163`
- Test: `shared/src/commonTest/kotlin/org/siloserver/silo/viewmodel/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `ResolvedSection`, `HomeSectionItemsResponse`, `ApiResult`, and the existing `SectionRepository.getHomeSectionItems(String)`.
- Produces:

```kotlin
data class HomeSectionHydration(
    val sections: List<ResolvedSection>,
    val fullyResolved: Boolean,
)

suspend fun hydrateHomeSections(
    sections: List<ResolvedSection>,
    maxConcurrency: Int = 4,
    fetchItems: suspend (String) -> ApiResult<HomeSectionItemsResponse>,
): HomeSectionHydration
```

- [ ] **Step 1: Write the failing inline-section, response-shape, and concurrency tests**

Create `HomeSectionHydratorTest` with literal fixtures and tests proving:

```kotlin
@Test
fun inlineSectionsRequireNoFallbackRequests() = runTest {
    var calls = 0
    val result = hydrateHomeSections(listOf(section("inline", total = 1, items = listOf(item("a"))))) {
        calls += 1
        error("fallback must not run")
    }
    assertEquals(0, calls)
    assertEquals(listOf("a"), result.sections.single().items.map { it.contentId })
    assertTrue(result.fullyResolved)
}

@Test
fun topLevelFallbackItemsHydrateTheOriginalSection() = runTest {
    val result = hydrateHomeSections(listOf(section("missing", total = 1))) {
        ApiResult.Success(HomeSectionItemsResponse(items = listOf(item("b"))))
    }
    assertEquals("missing", result.sections.single().id)
    assertEquals(listOf("b"), result.sections.single().items.map { it.contentId })
    assertTrue(result.fullyResolved)
}

@Test
fun failedFallbackMarksSnapshotPartialAndOmitsEmptySection() = runTest {
    val result = hydrateHomeSections(listOf(section("missing", total = 1))) {
        ApiResult.NetworkError(IllegalStateException("offline"))
    }
    assertTrue(result.sections.isEmpty())
    assertFalse(result.fullyResolved)
}
```

Add a fourth test with twelve unresolved sections, two
`CompletableDeferred<Unit>` gates, and atomic active/maximum counters. Hold the
first four requests at the gate, assert no fifth request starts, release them in
batches, and finally assert `maximum == 4`, all twelve IDs were fetched exactly
once, and output order matches input order.

- [ ] **Step 2: Run the hydrator tests and verify RED**

Run:

```bash
./gradlew :shared:testDebugUnitTest --tests '*HomeSectionHydratorTest' --no-daemon
```

Expected: compilation failure because `hydrateHomeSections` and `HomeSectionHydration` do not exist.

- [ ] **Step 3: Implement the pure hydrator with a four-request semaphore**

Use `kotlinx.coroutines.sync.Semaphore` and `withPermit` inside `coroutineScope`. Preserve inline sections without invoking `fetchItems`; fetch only `items.isEmpty() && totalCount > 0`; resolve nested `section.items`, nested zero-total responses, and top-level `items` with the same precedence currently used by `HomeViewModel`.

- [ ] **Step 4: Run the hydrator tests and verify GREEN**

Run:

```bash
./gradlew :shared:testDebugUnitTest --tests '*HomeSectionHydratorTest' --no-daemon
```

Expected: all `HomeSectionHydratorTest` cases pass.

- [ ] **Step 5: Write caller regression tests before changing either caller**

Extend `HomeViewModelTest` so a repository returning fully inline aggregate
sections records zero `getHomeSectionItems` calls while still updating UI and
cache. Create
`android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/startup/StartupHomeHydrationTest.kt`
with Android startup-shaped fixtures that assert the same zero-call contract.

- [ ] **Step 6: Run the caller tests and verify RED**

Run:

```bash
./gradlew \
  :shared:testDebugUnitTest --tests '*HomeViewModelTest*inline*' \
  :android-shared:testDebugUnitTest --tests '*StartupHomeHydrationTest' \
  --max-workers=2 --no-daemon
```

Expected: the startup test reports one fallback request per inline section;
the shared ViewModel assertion protects the already-correct inline behavior.

- [ ] **Step 7: Replace duplicated resolution code in both callers**

In `HomeViewModel.fetchSections`, call:

```kotlin
val hydration = hydrateHomeSections(sections) { sectionId ->
    sectionRepository.getHomeSectionItems(sectionId)
}
val resolved = hydration.sections
val fullyResolved = hydration.fullyResolved
```

In `StartupWarmup.warmHome`, call the same function and cache/warm artwork only when `fullyResolved` is true and `sections` is nonempty. This removes the unconditional per-section N+1.

- [ ] **Step 8: Run focused caller tests**

Run:

```bash
./gradlew \
  :shared:testDebugUnitTest --tests '*HomeSectionHydratorTest' --tests '*HomeViewModelTest' \
  :android-shared:testDebugUnitTest --tests '*StartupHomeHydrationTest' \
  --max-workers=2 --no-daemon
```

Expected: all focused tests pass with no fallback request for inline sections and a maximum of four for unresolved sections.

- [ ] **Step 9: Commit Task 1**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeSectionHydrator.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/viewmodel/HomeSectionHydratorTest.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/viewmodel/HomeViewModelTest.kt \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/startup/StartupWarmup.kt \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/startup/StartupHomeHydrationTest.kt
git commit -m "perf(android): hydrate inline home sections once"
```

### Task 2: Add cache-first speculative detail reads

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/CatalogRepository.kt:110-125`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/repository/CatalogRepositoryDetailCacheTest.kt`

**Interfaces:**
- Consumes: existing `CatalogCachePort.getCachedItemDetail` and network-first `getItemDetail`.
- Produces:

```kotlin
suspend fun getItemDetailForPrefetch(contentId: String): ApiResult<ItemDetail>
```

- [ ] **Step 1: Write failing cache-hit and cache-miss tests**

Extend `CatalogRepositoryDetailCacheTest`:

```kotlin
@Test
fun prefetchUsesCachedDetailWithoutNetwork() = runTest {
    val cache = FakeCache(preset = ItemDetail(contentId = "c1", type = "movie", title = "Cached"))
    val result = repoThatFailsOnNetwork(cache).getItemDetailForPrefetch("c1")
    assertEquals("Cached", (result as ApiResult.Success).data.title)
}

@Test
fun prefetchFetchesAndCachesWhenDetailIsAbsent() = runTest {
    val cache = FakeCache()
    val result = repo(HttpStatusCode.OK, """{"content_id":"c2","type":"movie","title":"Fresh"}""", cache)
        .getItemDetailForPrefetch("c2")
    assertEquals("Fresh", (result as ApiResult.Success).data.title)
    assertEquals("c2", cache.cachedId)
}
```

- [ ] **Step 2: Run the repository tests and verify RED**

Run:

```bash
./gradlew :shared:testDebugUnitTest --tests '*CatalogRepositoryDetailCacheTest' --no-daemon
```

Expected: compilation failure because `getItemDetailForPrefetch` does not exist.

- [ ] **Step 3: Implement the minimal cache-first method**

```kotlin
suspend fun getItemDetailForPrefetch(contentId: String): ApiResult<ItemDetail> {
    catalogCache.getCachedItemDetail(contentId)?.let { return ApiResult.Success(it) }
    return getItemDetail(contentId)
}
```

Do not change `getItemDetail`.

- [ ] **Step 4: Run the repository tests and verify GREEN**

Run:

```bash
./gradlew :shared:testDebugUnitTest --tests '*CatalogRepositoryDetailCacheTest' --no-daemon
```

Expected: every cache test passes; the existing network-first tests remain unchanged.

- [ ] **Step 5: Commit Task 2**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/repository/CatalogRepository.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/repository/CatalogRepositoryDetailCacheTest.kt
git commit -m "perf(tv): make marquee prefetch cache first"
```

### Task 3: Make Skyline prefetch settled, bounded, and demand-driven

**Files:**
- Create: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylinePrefetchPolicy.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvSkylinePrefetchPolicyTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt:95-341`
- Test: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeEnrichmentTest.kt`

**Interfaces:**
- Consumes: the raw focused content ID, the committed/rested marquee content ID, the focused row, and radius two.
- Produces:

```kotlin
internal fun settledPrefetchItems(
    items: List<SectionItem>,
    rawFocusedContentId: String?,
    settledContentId: String?,
    radius: Int = 2,
): List<SectionItem>
```

The function returns an empty list unless raw and settled identities match. When they match, it returns at most two neighbors on each side, excluding the focused item.

- [ ] **Step 1: Write the failing policy tests**

Create literal five-card fixtures and assert:

```kotlin
@Test
fun rapidFocusBeforeMarqueeSettlementStartsNoNeighborWork() {
    assertEquals(
        emptyList(),
        settledPrefetchItems(items, rawFocusedContentId = "d", settledContentId = "b"),
    )
}

@Test
fun settledFocusReturnsOnlyTwoNeighborsPerSide() {
    assertEquals(
        listOf("a", "b", "d", "e"),
        settledPrefetchItems(items, rawFocusedContentId = "c", settledContentId = "c")
            .map { it.contentId },
    )
}
```

Also cover first-card and missing-ID boundaries.

- [ ] **Step 2: Run the policy tests and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest --tests '*TvSkylinePrefetchPolicyTest' --no-daemon
```

Expected: compilation failure because `settledPrefetchItems` does not exist.

- [ ] **Step 3: Implement the pure settled-focus policy**

Use `indexOfFirst`, a clamped inclusive range, and `takeIf` identity equality. Return values in row order and exclude the focused index.

- [ ] **Step 4: Run the policy tests and verify GREEN**

Run the same command and confirm all boundary cases pass.

- [ ] **Step 5: Remove unconditional page-entry preload effects**

Delete only the two `LaunchedEffect(rows)`/`LaunchedEffect(rows, fetchDetail)` blocks that preload hero artwork and full details for two rows × eight items. Remove `HeroPreloadRowCount` and `HeroPreloadItemsPerRow`. Retain the initial aggregate-data marquee seed and startup artwork plan.

- [ ] **Step 6: Wire cache-first settled neighbor work**

Change the injected detail lambda to call `catalogRepository.getItemDetailForPrefetch`. Replace the raw-index neighbor calculation with `settledPrefetchItems`. Key the effect on `rows`, `focusedContentId`, `marquee.content?.contentId`, and the fetcher. A raw focus move cancels the old job immediately; the identity mismatch starts no new work until the marquee commits after its existing 150 ms rest.

Keep the existing maximum four neighbors, request claim, stale-result guard, artwork sizing, and composition-cancellation ownership.

- [ ] **Step 7: Add an integration-level request-budget test**

Extend `TvFocusMarqueeEnrichmentTest` with a coroutine test that sends focus identities `a`, `b`, `c` within less than 150 ms and proves only the settled `c` policy window is returned. Mutate the equality guard locally to verify the test fails by returning the `b` window, then restore the implementation.

- [ ] **Step 8: Run the Skyline and marquee suite**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests '*TvSkylinePrefetchPolicyTest' \
  --tests '*TvFocusMarqueeModelTest' \
  --tests '*TvFocusMarqueeEnrichmentTest' \
  --max-workers=2 --no-daemon
```

Expected: all focused tests pass.

- [ ] **Step 9: Commit Task 3**

```bash
git add androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylinePrefetchPolicy.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvSkylinePrefetchPolicyTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeEnrichmentTest.kt
git commit -m "perf(tv): defer skyline work until focus settles"
```

### Task 4: Bridge For You filters into recommendation rows

**Files:**
- Create: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsFocusBridge.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsFocusBridgeTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsScreen.kt:64-289`

**Interfaces:**
- Consumes: row-container/card focus request lambdas and a one-frame suspension.
- Produces:

```kotlin
internal suspend fun requestRecommendationRowFocus(
    requestRowContainer: () -> Boolean,
    awaitFrame: suspend () -> Unit,
    requestFirstCard: () -> Boolean,
): Boolean

internal fun shouldBridgeRecommendationsDown(
    showingRecommendations: Boolean,
    hasVisibleRecommendations: Boolean,
): Boolean
```

The function returns false only when the row container cannot accept focus. After a successful row hop it waits one frame and targets the first card.

- [ ] **Step 1: Write the failing focus-bridge tests**

Create tests with a literal event list:

```kotlin
@Test
fun handoffCrossesRowRestorerBeforeTargetingFirstCard() = runTest {
    val events = mutableListOf<String>()
    val handled = requestRecommendationRowFocus(
        requestRowContainer = { events += "row"; true },
        awaitFrame = { events += "frame" },
        requestFirstCard = { events += "card"; true },
    )
    assertTrue(handled)
    assertEquals(listOf("row", "frame", "card"), events)
}

@Test
fun rejectedRowHopDoesNotTargetCard() = runTest {
    val events = mutableListOf<String>()
    val handled = requestRecommendationRowFocus(
        requestRowContainer = { events += "row"; false },
        awaitFrame = { events += "frame" },
        requestFirstCard = { events += "card"; true },
    )
    assertFalse(handled)
    assertEquals(listOf("row"), events)
}
```

- [ ] **Step 2: Add the failing visibility-policy tests**

Assert that `shouldBridgeRecommendationsDown` returns true only when For You is
selected and at least one recommendation row is visible. Assert false for
Watchlist, Favorites, loading, error, and empty For You states.

- [ ] **Step 3: Run the bridge tests and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest --tests '*TvRecommendationsFocusBridgeTest' --no-daemon
```

Expected: compilation failure because the bridge does not exist.

- [ ] **Step 4: Implement the minimal bridge**

Implement exactly the ordered row/frame/card sequence and the two-boolean
visibility policy. Do not retry or add delays; `TvMediaRow` owns the row
`focusRestorer` contract.

- [ ] **Step 5: Run the bridge tests and verify GREEN**

Run the same command and confirm both ordering and rejected-hop behavior pass.

- [ ] **Step 6: Wire stable requesters into the screen**

Add stable requesters for For You, Watchlist, Favorites, the first recommendation row container, and its first card. Render recommendation rows with indexed items so only index zero receives:

```kotlin
firstItemFocusRequester = recommendationFirstCardFocusRequester
rowContainerFocusRequester = recommendationFirstRowContainerFocusRequester
onDirectionUp = {
    selectedFilterRequester.requestFocus()
}
```

Each filter pill's `onDirectionDown` consults
`shouldBridgeRecommendationsDown`; Watchlist and Favorites continue to use
their existing geometric grid navigation when selected. Keep initial entry on
Watchlist. The first For You row's `onDirectionUp` requests the For You pill.

- [ ] **Step 7: Run the complete focused TV suite**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests '*TvRecommendationsFocusBridgeTest' \
  --tests '*TvSkylinePrefetchPolicyTest' \
  --tests '*TvFocusMarqueeModelTest' \
  --tests '*TvFocusMarqueeEnrichmentTest' \
  --max-workers=2 --no-daemon
```

Expected: every focused test passes.

- [ ] **Step 8: Commit Task 4**

```bash
git add androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsFocusBridge.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsScreen.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsFocusBridgeTest.kt
git commit -m "fix(tv): enter for-you rows from filter controls"
```

### Task 5: Make the Home-to-menu boundary deliberate

**Files:**
- Create: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineUpNavigation.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineUpNavigationTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt:277-318`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreen.kt:38-216`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt:369-401,789-810`

**Interfaces:**
- Consumes: `focusedRowIndex`, `rows.indices`, Android `KeyEvent.nativeKeyEvent.repeatCount`, and the existing shell-owned content-to-menu handoff.
- Produces:

```kotlin
internal enum class TvSkylineUpAction {
    EnterMenu,
    StayInContent,
    TryPreviousRow,
}

internal fun tvSkylineUpAction(
    currentRow: Int,
    rowCount: Int,
    isRepeat: Boolean,
    relocationInFlight: Boolean,
): TvSkylineUpAction
```

The content fallback callback becomes `(isRepeat: Boolean) -> Boolean`. `true`
means the feed consumed the event; `false` means the shell may focus the top
menu.

- [ ] **Step 1: Write the failing pure focus-boundary tests**

Create `TvSkylineUpNavigationTest`:

```kotlin
@Test
fun heldUpStopsOnFirstContentRow() {
    assertEquals(
        TvSkylineUpAction.StayInContent,
        tvSkylineUpAction(currentRow = 0, rowCount = 6, isRepeat = true, relocationInFlight = false),
    )
}

@Test
fun freshUpFromFirstContentRowMayEnterMenu() {
    assertEquals(
        TvSkylineUpAction.EnterMenu,
        tvSkylineUpAction(currentRow = 0, rowCount = 6, isRepeat = false, relocationInFlight = false),
    )
}

@Test
fun repeatedInputDuringOffscreenRelocationIsConsumed() {
    assertEquals(
        TvSkylineUpAction.StayInContent,
        tvSkylineUpAction(currentRow = 4, rowCount = 6, isRepeat = true, relocationInFlight = true),
    )
}

@Test
fun ordinaryUpWithinRowsTriesExactlyOnePreviousRow() {
    assertEquals(
        TvSkylineUpAction.TryPreviousRow,
        tvSkylineUpAction(currentRow = 4, rowCount = 6, isRepeat = false, relocationInFlight = false),
    )
}
```

- [ ] **Step 2: Run the boundary tests and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests '*TvSkylineUpNavigationTest' \
  --max-workers=2 --no-daemon
```

Expected: compilation fails because `TvSkylineUpAction` and
`tvSkylineUpAction` do not exist.

- [ ] **Step 3: Implement the pure policy**

Create `TvSkylineUpNavigation.kt`:

```kotlin
package org.siloserver.silo.tv.ui.components

internal enum class TvSkylineUpAction {
    EnterMenu,
    StayInContent,
    TryPreviousRow,
}

internal fun tvSkylineUpAction(
    currentRow: Int,
    rowCount: Int,
    isRepeat: Boolean,
    relocationInFlight: Boolean,
): TvSkylineUpAction = when {
    relocationInFlight -> TvSkylineUpAction.StayInContent
    currentRow !in 0 until rowCount ->
        if (isRepeat) TvSkylineUpAction.StayInContent else TvSkylineUpAction.EnterMenu
    currentRow == 0 ->
        if (isRepeat) TvSkylineUpAction.StayInContent else TvSkylineUpAction.EnterMenu
    else -> TvSkylineUpAction.TryPreviousRow
}
```

- [ ] **Step 4: Run the pure tests and verify GREEN**

Run the Step 2 command. Expected: all four tests pass.

- [ ] **Step 5: Wire repeat identity and serialized relocation**

Change `onContentUpFallbackChanged` through `TvHomeScreen`,
`TvHomeContent`, and `TvSkylineSectionFeed` to carry
`((isRepeat: Boolean) -> Boolean)`.

In `TvSkylineSectionFeed`, remember `rowRelocationInFlight`. For
`TryPreviousRow`, first call `focusManager.moveFocus(FocusDirection.Up)`. If
that fails, set `rowRelocationInFlight = true`, launch exactly one
`animateScrollToItem(currentRow - 1)` job, await one frame, attempt the focus
move, and clear the flag in `finally`. `StayInContent` returns `true`;
`EnterMenu` returns `false`.

In `TvMainShell`, pass:

```kotlin
val isRepeat = ev.nativeKeyEvent.repeatCount > 0
val contentHandledUp = contentUpFallback?.invoke(isRepeat)
```

Keep the shell's existing `focusState.requestMenuFocus()` behavior only when
the active feed returns `false`.

- [ ] **Step 6: Run the boundary test and TV compilation**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests '*TvSkylineUpNavigationTest' \
  :androidTvApp:compileDebugKotlin \
  --max-workers=2 --no-daemon
```

Expected: tests and compilation pass.

- [ ] **Step 7: Commit Task 5**

```bash
git add \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineUpNavigation.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineUpNavigationTest.kt
git commit -m "fix(tv): stop held up at the first home row"
```

### Task 6: Route For You saved lists through one presentation

**Files:**
- Create: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvForYouEntryRequest.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvForYouEntryRequestTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsScreen.kt:67-117`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt:971-976,1224-1242,1290-1307`

**Interfaces:**
- Consumes: the existing `TvMainRoute.ForYou`, `TvRecommendationsScreen`,
  `TvWatchlistInline`, `TvFavoritesInline`, and standalone profile-menu routes.
- Produces:

```kotlin
internal enum class SavedListSelection {
    Watchlist,
    Favorites,
}

internal data class TvForYouEntryRequest(
    val sequence: Int = 0,
    val selection: SavedListSelection? = null,
) {
    fun next(selection: SavedListSelection?): TvForYouEntryRequest =
        TvForYouEntryRequest(sequence = sequence + 1, selection = selection)
}

internal data class AppliedForYouSelection(
    val selection: SavedListSelection?,
    val lastAppliedSequence: Int,
)

internal fun applyForYouEntryRequest(
    currentSelection: SavedListSelection?,
    lastAppliedSequence: Int,
    request: TvForYouEntryRequest,
): AppliedForYouSelection
```

`TvRecommendationsScreen` accepts
`entryRequest: TvForYouEntryRequest = TvForYouEntryRequest()` and applies its
selection only when `entryRequest.sequence` changes.

- [ ] **Step 1: Write the failing request-state tests**

Create `TvForYouEntryRequestTest`:

```kotlin
@Test
fun repeatedSelectionStillCreatesANewRequest() {
    val first = TvForYouEntryRequest().next(SavedListSelection.Watchlist)
    val second = first.next(SavedListSelection.Watchlist)

    assertEquals(1, first.sequence)
    assertEquals(2, second.sequence)
    assertEquals(SavedListSelection.Watchlist, second.selection)
}

@Test
fun recommendationsRequestClearsSavedListSelection() {
    val request = TvForYouEntryRequest(
        sequence = 4,
        selection = SavedListSelection.Favorites,
    ).next(null)

    assertEquals(5, request.sequence)
    assertNull(request.selection)
}

@Test
fun unrelatedRecompositionDoesNotOverrideInPageSelection() {
    val applied = applyForYouEntryRequest(
        currentSelection = SavedListSelection.Favorites,
        lastAppliedSequence = 3,
        request = TvForYouEntryRequest(
            sequence = 3,
            selection = SavedListSelection.Watchlist,
        ),
    )

    assertEquals(SavedListSelection.Favorites, applied.selection)
    assertEquals(3, applied.lastAppliedSequence)
}

@Test
fun newerRequestAppliesRequestedInlineSelection() {
    val applied = applyForYouEntryRequest(
        currentSelection = null,
        lastAppliedSequence = 3,
        request = TvForYouEntryRequest(
            sequence = 4,
            selection = SavedListSelection.Watchlist,
        ),
    )

    assertEquals(SavedListSelection.Watchlist, applied.selection)
    assertEquals(4, applied.lastAppliedSequence)
}
```

- [ ] **Step 2: Run the request-state tests and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests '*TvForYouEntryRequestTest' \
  --max-workers=2 --no-daemon
```

Expected: compilation fails because the request and applied-selection types do
not exist.

- [ ] **Step 3: Implement the repeatable entry request**

Create `TvForYouEntryRequest.kt` with the exact interfaces above. The apply
function returns the current selection unchanged when
`request.sequence <= lastAppliedSequence`; otherwise it returns the request's
selection and sequence. Move
`SavedListSelection` out of `TvRecommendationsScreen.kt` into that file.

Add the screen parameter:

```kotlin
entryRequest: TvForYouEntryRequest = TvForYouEntryRequest(),
```

Initialize selection and the last applied sequence with `remember`, then add:

```kotlin
LaunchedEffect(entryRequest.sequence) {
    val applied = applyForYouEntryRequest(
        currentSelection = savedListSelection,
        lastAppliedSequence = lastAppliedEntrySequence,
        request = entryRequest,
    )
    savedListSelection = applied.selection
    lastAppliedEntrySequence = applied.lastAppliedSequence
}
```

This permits the same top-menu choice to be selected repeatedly and does not
overwrite in-page pill changes during unrelated recompositions.

- [ ] **Step 4: Wire only the For You selector to inline requests**

In `TvMainShell`, remember:

```kotlin
var forYouEntryRequest by remember { mutableStateOf(TvForYouEntryRequest()) }
val openForYou: (SavedListSelection?) -> Unit = { selection ->
    forYouEntryRequest = forYouEntryRequest.next(selection)
    focusState.closePanel(false)
    navigateToSecondary(TvMainRoute.ForYou.route)
    moveFocusToContent(TvMainRoute.ForYou.route)
}
```

Pass `entryRequest = forYouEntryRequest` to `TvRecommendationsScreen`. Wire
the three `TvForYouSelector` callbacks to:

```kotlin
onWatchlist = { openForYou(SavedListSelection.Watchlist) }
onFavorites = { openForYou(SavedListSelection.Favorites) }
onRecommendations = { openForYou(null) }
```

Do not change `TvProfileDropdown` callbacks: they must continue navigating to
`TvMainRoute.Watchlist` and `TvMainRoute.Favorites`.

- [ ] **Step 5: Run focused routing and existing focus tests**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests '*TvForYouEntryRequestTest' \
  --tests '*TvRecommendationsFocusBridgeTest' \
  --max-workers=2 --no-daemon
```

Expected: all tests pass.

- [ ] **Step 6: Manually inspect the two call-site groups**

Run:

```bash
git diff -- \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsScreen.kt
```

Confirm the `TvForYouSelector` callbacks all invoke `openForYou`, while the
`TvProfileDropdown` Watchlist/Favorites callbacks still navigate directly to
their standalone routes.

- [ ] **Step 7: Commit Task 6**

```bash
git add \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvForYouEntryRequest.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvForYouEntryRequestTest.kt
git commit -m "fix(tv): unify for-you saved-list routes"
```

### Task 7: Full verification, production-shaped smoke, and executive summary

**Files:**
- Create: `docs/reviews/2026-07-27-android-tv-navigation-remediation-executive-summary.md`
- Modify only if verification exposes a feature regression: files already listed in Tasks 1-4.

**Interfaces:**
- Consumes: all prior task commits and an explicitly approved authenticated TV
  test target.
- Produces: a concise executive summary containing impact, causes, remedy, quantified before/after evidence, rollout risk, and rollback boundary.

- [ ] **Step 1: Run supply-chain and complete relevant unit gates**

Run:

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
./gradlew :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --max-workers=2 --no-daemon
```

Expected: exit zero with no failed test task.

- [ ] **Step 2: Compile debug and minified TV release**

Run:

```bash
./gradlew \
  :androidTvApp:assembleDebug \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2 --no-daemon
```

Expected: both tasks exit zero.

- [ ] **Step 3: Perform the For You device smoke**

Install only on the explicitly selected Shield if the candidate signer is compatible with the installed package; otherwise use the dedicated TV emulator. Preserve app data with:

```bash
TV_TEST_SERIAL="<approved-tv-test-serial>"
adb -s "$TV_TEST_SERIAL" install -r \
  androidTvApp/build/outputs/apk/release/androidTvApp-universal-release.apk
```

Verify:

1. Enter For You from the top menu.
2. Initial focus remains Watchlist.
3. Select For You and press Down; focus reaches the first visible recommendation card.
4. Press Down/Up repeatedly; focus is not trapped and Up returns to For You.
5. Select Watchlist and Favorites from the in-page pills; Down enters their inline grids.
6. Select Watchlist and Favorites from the top For You selector; each opens the
   same inline For You presentation with the matching pill selected.
7. Select Watchlist and Favorites from the profile menu; each retains its
   standalone utility-page presentation.
8. From several Home rows down, hold Up until the first row is reached; the
   repeated sequence stays in content, and a released-then-fresh Up enters the
   selected top-menu item.

- [ ] **Step 4: Repeat the cold/warm performance protocol**

Against `lib.strm.cafe`, clear only process state with `am force-stop`, reset `dumpsys gfxinfo`, clear logcat, launch, and run the same fixed sequences used during diagnosis:

- horizontal: five Right then five Left at 250 ms;
- vertical: three Down then three Up at 500 ms;
- repeat each immediately without process restart.

Record total/janky frames, p50/p90/p95/p99, sanitized HTTP completion count, GC count, crash, and ANR status. Do not capture credentials or request headers.

- [ ] **Step 5: Write the executive summary**

Create the summary with these exact sections:

- `Decision`: what was fixed and why it is safe to ship.
- `Customer impact`: For You accessibility and first-traversal responsiveness.
- `Verified causes`: missing focus bridge, duplicate Home hydration, eager detail
  fan-out, an unguarded repeated-Up boundary, and two For You saved-list routes.
- `Change`: inline-first bounded hydration, cache-first rested enrichment,
  deliberate focus handoff, serialized row relocation, and unified For You
  selector presentation.
- `Evidence`: before/after cold and warm measurements and test/build counts.
- `Risk and rollback`: Android TV focus/prefetch scope, no schema/server change, revert commits independently.

- [ ] **Step 6: Review diff and obtain independent review**

Run:

```bash
git diff --check origin/main...HEAD
git status --short
git log --oneline origin/main..HEAD
```

Request focused review of correctness, coroutine cancellation, focus-restorer ordering, cache semantics, and whether tests detect realistic regressions. Fix every Critical or Important finding test-first and rerun the smallest affected gate.

- [ ] **Step 7: Commit the verified executive summary**

```bash
git add docs/reviews/2026-07-27-android-tv-navigation-remediation-executive-summary.md
git commit -m "docs(tv): summarize navigation remediation"
```

- [ ] **Step 8: Run the final fresh gate**

Run:

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
./gradlew :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2 --no-daemon
```

Expected: exit zero. Confirm `git status --short` is empty before invoking `superpowers:finishing-a-development-branch`.
