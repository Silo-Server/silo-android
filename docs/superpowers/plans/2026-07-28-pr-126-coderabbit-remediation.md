# PR #126 CodeRabbit Remediation Implementation Plan

> **Status:** Completed 2026-07-28. All remediation tasks and their recorded verification/review steps were completed on `fix/tv-for-you-cold-navigation`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkboxes for tracking.

**Goal:** Resolve every substantiated CodeRabbit finding on PR #126 while preserving intentional shared-request and Watch Together delivery semantics.

**Architecture:** Keep each correction at its current boundary: TV shell wiring owns destination/focus routing, the marquee state owns page-entry versus real-focus arbitration, phone/TV presentation helpers own metadata validation, and repositories own identity-safe cache writes. Add focused regressions before behavioral changes; use existing characterization tests for behavior-preserving refactors.

**Tech Stack:** Kotlin 2.1, Jetpack Compose, Kotlin coroutines, Kotlin test/JUnit, Gradle, shell supply-chain policy scripts.

## Global Constraints

- Work only on `fix/tv-for-you-cold-navigation`; do not merge PR #126.
- Preserve shared in-flight recommendation requests; do not cancel repository work when one UI caller is superseded.
- Preserve Watch Together attach, cadence, and delivery semantics.
- Settled real TV card focus always wins over a page-entry marquee seed.
- IMDb ratings are valid only when finite and within `(0, 10]` on phone and TV.
- Do not install or deploy APKs.
- Address proven false positives with explicit invariants or documentation, not semantic changes.

---

## File map

- `androidTvApp/.../TvMainShell.kt`: root-destination selection and For You request reset.
- `androidTvApp/.../TvLibraryDetailScreen.kt`: Alphabet rail fallback forwarding.
- `androidTvApp/.../TvForYouEntryRequest.kt`: testable request transition.
- `androidTvApp/.../TvSkylineSectionFeed.kt`: initial marquee seed effect identity.
- `androidTvApp/.../TvFocusMarqueeModel.kt`: page-entry seed arbitration and TV metadata validation.
- `androidApp/.../FeaturedHeroMetadata.kt`: phone metadata validation.
- `shared/.../RoomDeliveryLatch.kt`: compiler-visible nullable-key invariant.
- Phone/TV room-sync controllers: non-null delivery-key binding at state-report call sites.
- `shared/.../SectionRepository.kt`: injectable home-request dispatcher.
- Phone library state logic and shared repositories: behavior-preserving helper extractions.
- Existing focused unit-test files plus small source-contract tests: regressions and wiring verification.
- Three review/plan/report documents: wording, stale references, and local-path hygiene.

### Task 1: TV destination and focus wiring

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/library/TvLibraryDetailScreen.kt:158-167`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt:548-601`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvForYouEntryRequest.kt:8-14`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvForYouEntryRequestTest.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/library/TvLibraryReviewWiringSourceTest.kt`

**Interfaces:**
- Consumes: `TvForYouEntryRequest.next(selection: SavedListSelection?)`.
- Produces: `TvForYouEntryRequest.nextForTopLevelForYou(): TvForYouEntryRequest`; Alphabet and Calendar both register their content-Up fallback with the shell.

- [x] **Step 1: Write the For You RED regression**

Add to `TvForYouEntryRequestTest`:

```kotlin
@Test
fun topLevelForYouRequestClearsSavedListSelection() {
    val request = TvForYouEntryRequest(
        sequence = 9,
        selection = SavedListSelection.Watchlist,
    ).nextForTopLevelForYou()

    assertEquals(10, request.sequence)
    assertNull(request.selection)
}
```

- [x] **Step 2: Write the Alphabet wiring RED regression**

Create a source-contract test that loads
`TvLibraryDetailScreen.kt`, isolates the
`TvLibraryTab.Alphabet -> LibraryTab(...)` block, and asserts it contains:

```kotlin
onContentUpFallbackChanged = onContentUpFallbackChanged
```

Also assert the existing `TvCalendarScreen(...)` call still contains
`onContentUpFallbackChanged = onContentUpFallback`.

- [x] **Step 3: Run RED**

Run:

```bash
./gradlew --no-daemon :androidTvApp:testDebugUnitTest \
  --tests '*TvForYouEntryRequestTest' \
  --tests '*TvLibraryReviewWiringSourceTest' \
  --max-workers=2
```

Expected: failure because `nextForTopLevelForYou` is absent and the Alphabet
branch does not forward the fallback.

- [x] **Step 4: Implement the minimal GREEN changes**

Add:

```kotlin
fun nextForTopLevelForYou(): TvForYouEntryRequest = next(null)
```

At the start of `onSelectRoot`, update only the For You destination:

```kotlin
if (dest == TvRootDestination.ForYou) {
    forYouEntryRequest = forYouEntryRequest.nextForTopLevelForYou()
}
```

Forward `onContentUpFallbackChanged` in the Alphabet `LibraryTab` call exactly
as Browse already does.

- [x] **Step 5: Run GREEN and focused neighboring tests**

Run:

```bash
./gradlew --no-daemon :androidTvApp:testDebugUnitTest \
  --tests '*TvForYouEntryRequestTest' \
  --tests '*TvLibraryReviewWiringSourceTest' \
  --tests '*TvCalendarFocusRoutingTest' \
  --tests '*TvLibraryFocusRestoreTest' \
  --max-workers=2
```

Expected: all selected tests pass.

- [x] **Step 6: Commit Task 1**

```bash
git add androidTvApp/src/androidMain androidTvApp/src/androidUnitTest
git commit -m "fix(tv): close reviewed focus routing gaps"
```

### Task 2: Marquee identity and metadata bounds

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt:106-123`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModel.kt:76-165,229-285`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeModelTest.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarqueeEnrichmentTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadata.kt:18-55`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedHeroMetadataTest.kt`

**Interfaces:**
- Consumes: `TvFocusMarqueeState.seedInitialPreview`, `TvMarqueeContent.from`, and `featuredHeroMetadata`.
- Produces: identity-aware page-entry reseeding that stops after real focus; consistent `(0, 10]` rating tokens on both clients.

- [x] **Step 1: Write page-entry identity RED tests**

Add tests that:

```kotlin
state.seedInitialPreview(item, "Row", rowIdentity = "row-old")
state.commit(state.candidate)
state.seedInitialPreview(item, "Row", rowIdentity = "row-new")
assertEquals("row-new#item-1", state.candidate?.id)
```

and:

```kotlin
state.preview(focusedItem, "Focused", rowIdentity = "focused-row")
state.commit(state.candidate)
state.seedInitialPreview(seedItem, "Replacement", rowIdentity = "replacement-row")
assertEquals("focused-row#focused-item", state.content?.id)
assertEquals("focused-row#focused-item", state.candidate?.id)
```

- [x] **Step 2: Write phone/TV rating and mixed-field RED tests**

For both clients assert:

```kotlin
ratingImdb = 11.0
```

produces no rating token. Add two independent cases:

```kotlin
ratingImdb = Double.NaN
durationSeconds = 7_200.0
```

retains `"2h"`, while:

```kotlin
ratingImdb = 8.4
durationSeconds = Double.NaN
```

retains `"8.4"`.

- [x] **Step 3: Run RED**

```bash
./gradlew --no-daemon \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*FeaturedHeroMetadataTest' \
  --tests '*TvFocusMarqueeModelTest' \
  --tests '*TvFocusMarqueeEnrichmentTest' \
  --max-workers=2
```

Expected: the row-identity and upper-bound assertions fail.

- [x] **Step 4: Implement minimal marquee arbitration**

Keep `seedInitialPreview` as the boundary. Build `next` first, ignore seeds once
`focusedMarqueeId != null`, and otherwise replace a different page-entry
candidate/content identity:

```kotlin
fun seedInitialPreview(item: SectionItem, rowTitle: String, rowIdentity: String = rowTitle) {
    if (focusedMarqueeId != null) return
    val next = TvMarqueeContent.from(item, rowTitle, rowIdentity)
    if (candidate?.id == next.id || content?.id == next.id) return
    candidate = next
}
```

Include `initialMarqueeSeed?.rowIdentity` in the `LaunchedEffect` key and call
`seedInitialPreview` without an outer `marquee.content == null` gate.

- [x] **Step 5: Implement bounded shared rating helpers**

In each client boundary use a small private helper equivalent to:

```kotlin
private fun validImdbRating(rating: Double?): Double? =
    rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
```

Route both TV episode/non-episode branches through one TV `ratingToken` helper
to remove the duplicated filter.

- [x] **Step 6: Run GREEN**

Repeat the Task 2 focused command. Expected: all selected tests pass.

- [x] **Step 7: Commit Task 2**

```bash
git add androidApp/src/androidMain androidApp/src/androidUnitTest \
  androidTvApp/src/androidMain androidTvApp/src/androidUnitTest
git commit -m "fix(android): bound hero metadata and marquee identity"
```

### Task 3: Compiler-visible Watch Together delivery invariants

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/watchtogether/RoomDeliveryLatch.kt:58-64`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/watchtogether/RoomDeliveryLatchTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/RoomSyncController.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvRoomSyncController.kt:204-234`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvRoomDeliveryKeySourceTest.kt`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/RoomDeliveryKeySourceTest.kt`

**Interfaces:**
- Consumes: `RoomDeliveryLatch.isServerAttached`.
- Produces: identical delivery decisions with no nullable-key dereference or force-unwrapped reporting key.

- [x] **Step 1: Strengthen nullable-key characterization**

Add latch cases asserting `false` for:

```kotlin
isServerAttached(key = null, echo = matchingEcho)
isServerAttached(key = validKey, echo = null)
isServerAttached(key = validKey, echo = wrongEpochEcho)
```

- [x] **Step 2: Write source-contract RED tests**

Assert each room-sync controller reporting block does not contain
`deliveryKey!!` and does contain an explicit `deliveryKey != null` guard before
`stateReport`.

- [x] **Step 3: Run RED**

```bash
./gradlew --no-daemon \
  :shared:test \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*RoomDeliveryLatchTest' \
  --tests '*RoomDeliveryKeySourceTest' \
  --tests '*TvRoomDeliveryKeySourceTest' \
  --max-workers=2
```

Historical pre-fix result: latch behavior remained green while both
source-contract tests failed because their reporting blocks used
`deliveryKey!!`. The current tree binds a non-null key before state reporting.

- [x] **Step 4: Make nullability explicit without semantic changes**

Use:

```kotlin
key != null &&
    isAttached(key) &&
    echo != null &&
    echo.connectionGeneration == key.connectionGeneration
```

in the latch. In both controllers require a non-null local key before
`isServerAttached` and pass `key.playbackSessionId` to `stateReport`.

- [x] **Step 5: Run GREEN and neighboring room-sync tests**

Run the Task 3 command plus `*RoomSyncStateReportGateTest`. Expected: all pass.

- [x] **Step 6: Commit Task 3**

```bash
git add shared/src/commonMain shared/src/commonTest \
  androidApp/src/androidMain androidApp/src/androidUnitTest \
  androidTvApp/src/androidMain androidTvApp/src/androidUnitTest
git commit -m "refactor(watch-together): make delivery keys explicit"
```

### Task 4: Behavior-preserving review cleanups

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/SectionRepository.kt:15-34`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/repository/SectionRepositoryCacheTest.kt:80-221`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt:688-708`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/CatalogRepository.kt:47-177`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/PersonalDataRepository.kt:33-45`
- Verify: `shared/src/commonTest/kotlin/org/siloserver/silo/repository/CatalogRepositoryDetailCacheTest.kt`
- Verify: `shared/src/commonTest/kotlin/org/siloserver/silo/repository/PersonalDataRepositoryCacheTest.kt`

**Interfaces:**
- Consumes: `CoroutineDispatcher`, identity transition generations, and `CatalogCacheWriteLease`.
- Produces: injectable home-request dispatcher and private helpers that preserve existing generation and cache-write decisions.

- [x] **Step 1: Establish the characterization-test baseline**

```bash
./gradlew --no-daemon :shared:test \
  --tests '*SectionRepositoryCacheTest' \
  --tests '*CatalogRepositoryDetailCacheTest' \
  --tests '*PersonalDataRepositoryCacheTest' \
  --max-workers=2
```

Expected: all selected tests pass before refactoring.

- [x] **Step 2: Inject the home request dispatcher**

Add a constructor parameter with the existing runtime default:

```kotlin
private val homeRequestDispatcher: CoroutineDispatcher = Dispatchers.Default
```

and use it in `homeRequestScope`. Update gated concurrency tests to pass a
`StandardTestDispatcher(testScheduler)` and extract one local helper that builds
the gated `MockEngine`/repository without changing assertions.

- [x] **Step 3: Extract the phone library identity comparison**

Add:

```kotlin
private fun CatalogRequestIdentity.matches(state: LibrariesUiState): Boolean =
    state.selectedLibraryId == libraryId &&
        state.browseSort == browseSort &&
        state.selectedNamePrefix == selectedNamePrefix &&
        state.filterState == filterState
```

Keep request/query generation comparisons in their respective methods.
Do not retain or cancel a recommendation `Job`; shared repository work must
remain independent of one superseded UI caller.

- [x] **Step 4: Extract repository-local guarded-write helpers**

In both repositories add the private pattern:

```kotlin
private suspend fun writeIfIdentityUnchanged(
    requestGeneration: Long,
    write: suspend (CatalogCacheWriteLease) -> Unit,
) {
    if (requestGeneration == identityTransitions.generation.value) {
        write(CatalogCacheWriteLease(requestGeneration))
    }
}
```

Route existing cache write sites through it without moving network calls or
changing success/fallback behavior.

- [x] **Step 5: Re-run characterization tests**

Repeat the Task 4 baseline command. Expected: all selected tests pass with the
same assertions.

- [x] **Step 6: Commit Task 4**

```bash
git add shared/src/commonMain shared/src/commonTest androidApp/src/androidMain
git commit -m "refactor(android): clarify reviewed request guards"
```

### Task 5: Documentation and privacy corrections

**Files:**
- Modify: `.superpowers/sdd/2026-07-28-android-tv-active-header-focus-editorial-hero/task-4-report.md`
- Modify: `docs/reviews/2026-07-27-android-tv-navigation-remediation-executive-summary.md:71`
- Modify: `docs/superpowers/plans/2026-07-28-android-tv-active-header-focus-editorial-hero.md:151,245`

**Interfaces:**
- Consumes: current helper and focus-routing names.
- Produces: reproducible repository-relative evidence and implementation-accurate plan text.

- [x] **Step 1: Replace local paths**

Replace the worktree with `fix/tv-for-you-cold-navigation worktree` and replace
Desktop artifact paths with the artifact filenames while retaining hashes and
signing notes.

- [x] **Step 2: Correct stale wording**

Change `TV focused` to `TV-focused`, replace the stale
`FeaturedCarousel.metadataChips` reference with `featuredHeroMetadata`, and
update focus pseudocode to use `requestMenuFocusIfAvailable` plus the
`(focusRequest, focusRequestTarget)` handled identity.

- [x] **Step 3: Verify documentation**

```bash
rg -n --pcre2 '/(Users|home)/[^/[:space:]]+' \
  .superpowers/sdd/2026-07-28-android-tv-active-header-focus-editorial-hero/task-4-report.md
rg -n 'FeaturedCarousel\\.metadataChips|TV focused|requestMenuFocus\\(selectedMenuFocusTarget\\)' \
  docs/reviews/2026-07-27-android-tv-navigation-remediation-executive-summary.md \
  docs/superpowers/plans/2026-07-28-android-tv-active-header-focus-editorial-hero.md
git diff --check
```

Expected: both `rg` commands return no matches and `git diff --check` passes.

- [x] **Step 4: Commit Task 5**

```bash
git add .superpowers/sdd/2026-07-28-android-tv-active-header-focus-editorial-hero/task-4-report.md \
  docs/reviews/2026-07-27-android-tv-navigation-remediation-executive-summary.md \
  docs/superpowers/plans/2026-07-28-android-tv-active-header-focus-editorial-hero.md
git commit -m "docs: resolve PR 126 review findings"
```

### Task 6: Full verification, independent review, and PR update

**Files:**
- Verify: all Task 1-5 files.
- Update remotely: PR #126 branch and description/check state only; do not merge.

**Interfaces:**
- Consumes: all correction commits.
- Produces: reviewed branch with fresh focused, full, supply-chain, and release evidence.

- [x] **Step 1: Run complete unit gates**

```bash
./gradlew --no-daemon \
  :shared:test \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --max-workers=2 --rerun-tasks
```

Expected: exit 0 with no failed tests.

- [x] **Step 2: Run supply-chain policy**

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Expected: both exit 0.

- [x] **Step 3: Run phone and TV release compilation**

```bash
./gradlew --no-daemon \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2
```

Expected: `BUILD SUCCESSFUL`; these artifacts are verification-only and are not
installed or deployed.

- [x] **Step 4: Perform diff and scope checks**

```bash
git diff --check origin/main...HEAD
git status --short
git log --oneline origin/main..HEAD
```

Expected: no whitespace errors, no uncommitted changes, and only the documented
PR #126 plus review-remediation commits.

- [x] **Step 5: Obtain independent focused review**

Request review of `f822d22c..HEAD` against this plan and the design spec. Require
explicit assessment of the marquee real-focus invariant, For You reset,
Alphabet fallback, request-sharing non-cancellation, Watch Together delivery
semantics, and test adequacy. Fix any critical or important finding with a new
focused RED/GREEN cycle.

- [x] **Step 6: Push and update PR #126**

Push `fix/tv-for-you-cold-navigation`, update the PR verification summary with
fresh commands, and leave the PR open and unmerged. Record which CodeRabbit
suggestion was intentionally rejected because cancelling a UI caller must not
cancel shared repository work.
