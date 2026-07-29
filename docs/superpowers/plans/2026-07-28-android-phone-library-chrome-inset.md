# Android Phone Library Chrome Inset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Android phone library content below the fixed library selector and subtab chrome, matching the iOS `safeAreaInset` behavior.

**Architecture:** Preserve the full-screen backdrop in the existing root `Box`, but replace the content-first/floating-chrome overlay with a foreground `Column`: measured chrome first, then a clipped `Box` with `weight(1f)` for every library state and subtab. Remove the old per-tab status-bar/chrome runways so system insets are consumed once by the shared chrome.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Kotlin/JVM unit tests, Gradle.

## Global Constraints

- Android phone only; do not alter Android TV, server APIs, Apple code, or standalone Browse / Collections routes.
- Keep the full-screen Recommended hero backdrop; only interactive/editorial scroll content is confined below the chrome.
- Preserve library/profile menu actions, tab state, scroll state, filters, pagination, grids, hero selection, and bottom-chrome padding.
- The shared library chrome consumes status-bar/display-cutout top inset exactly once.
- Do not target physical devices; emulator validation may use only the dedicated phone emulator when available.

---

### Task 1: Reserve a measured viewport below the shared library chrome

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt:610-1110`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt:83-125`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibraryChromeInsetSourceTest.kt`

**Interfaces:**
- Consumes: existing `LibrariesFloatingChrome`, `RecommendedTabContent`, `BrowseTabContent`, `CollectionsTabContent`, `FeaturedCarousel`, and `LocalBottomChromeInset`.
- Produces: one measured `Column` boundary in `LibrariesScreen`; a `FeaturedCarousel(topInset: Dp = 16.dp)` parameter that adds only content-local breathing room.

- [ ] **Step 1: Write the failing structural regression tests**

Create `LibraryChromeInsetSourceTest.kt` with repository-standard source loading:

```kotlin
package org.siloserver.silo.android.ui.screens.libraries

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryChromeInsetSourceTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private val libraries = source(
        "org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt",
    )
    private val carousel = source(
        "org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt",
    )

    @Test
    fun sharedChromeOwnsReservedSpaceBeforeEveryLibraryTab() {
        val chrome = libraries.indexOf("LibrariesFloatingChrome(")
        val viewport = libraries.indexOf("LibraryContentViewport(")
        assertTrue(chrome >= 0)
        assertTrue(viewport > chrome)
        assertTrue(libraries.contains("Modifier.weight(1f).clipToBounds()"))
    }

    @Test
    fun tabsDoNotCarryOverlayClearanceRunways() {
        assertFalse(libraries.contains("LibrariesChromeContentHeight"))
        assertFalse(libraries.contains("extraTopInset = 50.dp"))
        assertFalse(libraries.contains(".windowInsetsPadding(WindowInsets.statusBars)"))
        assertFalse(carousel.contains("WindowInsets.statusBars"))
        assertTrue(carousel.contains("topInset: androidx.compose.ui.unit.Dp = 16.dp"))
    }
}
```

- [ ] **Step 2: Run the source test and verify RED**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests org.siloserver.silo.android.ui.screens.libraries.LibraryChromeInsetSourceTest \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: FAIL because `LibraryContentViewport`, the reserved weighted/clipped viewport, and the simplified carousel inset do not exist; overlay-clearance constants remain.

- [ ] **Step 3: Put the chrome before a shared clipped viewport**

In `LibrariesScreen`, retain backdrop drawing in the root `Box`, then render:

```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    LibrariesFloatingChrome(
        scrimProgress = chromeScrimProgress,
        selectedLibrary = selectedLibrary,
        canSwitch = state.libraries.size > 1,
        activeProfile = activeProfile,
        selectedTab = state.selectedTab,
        onLibrarySelectorClick = onLibrarySelectorClick,
        onTabSelected = viewModel::selectTab,
        onSearchClick = onSearchClick,
        onRequestsClick = onRequestsClick,
        onWatchTogetherClick = onWatchTogetherClick,
        onSettingsClick = onSettingsClick,
        onSwitchProfileClick = onSwitchProfileClick,
        onSwitchServerClick = onSwitchServerClick,
        onSignOutClick = onSignOutClick,
    )

    LibraryContentViewport(
        modifier = Modifier.weight(1f).clipToBounds(),
    ) {
        when {
            state.isLoadingLibraries && state.libraries.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.librariesError != null && state.libraries.isEmpty() -> {
                ErrorView(
                    message = state.librariesError ?: "Failed to load libraries",
                    onRetry = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            selectedLibrary == null -> {
                EmptyStateView(
                    title = "No libraries available",
                    subtitle = "Libraries visible to this profile will show up here",
                    icon = Icons.Default.VideoLibrary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.selectedTab == LibrariesSubtab.Recommended -> {
                RecommendedTabContent(
                    state = state,
                    listState = recommendedListState,
                    onItemClick = onItemClick,
                    onPlayClick = onPlayClick,
                    onRetry = viewModel::retryCurrentTab,
                    onActiveBackdropChange = { url, thumbhash ->
                        heroBackdropUrl = url
                        heroBackdropThumbhash = thumbhash
                    },
                )
            }
            state.selectedTab == LibrariesSubtab.Browse -> {
                BrowseTabContent(
                    state = state,
                    onItemClick = onItemClick,
                    onRetry = viewModel::retryCurrentTab,
                    onLoadMore = viewModel::loadMoreCatalog,
                    onSortChanged = viewModel::selectBrowseSort,
                    onNamePrefixChanged = viewModel::selectNamePrefix,
                    onDensityChanged = viewModel::selectViewDensity,
                    onApplyFilter = viewModel::applyFilterState,
                    onSetPreserve = viewModel::setPreserveFilters,
                )
            }
            else -> {
                CollectionsTabContent(
                    state = state,
                    onCollectionClick = { collectionId ->
                        state.selectedLibraryId?.let { libraryId ->
                            onCollectionClick(collectionId, libraryId)
                        }
                    },
                    onRetry = viewModel::retryCurrentTab,
                )
            }
        }
    }
}
```

Add the focused wrapper next to `LibrariesScreen`:

```kotlin
@Composable
private fun LibraryContentViewport(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}
```

Import `androidx.compose.ui.draw.clipToBounds`.

- [ ] **Step 4: Remove duplicate top-clearance padding**

Delete `LibrariesChromeContentHeight`. In Recommended loading/error/empty
states, remove `.padding(top = LibrariesChromeContentHeight)` and
`.windowInsetsPadding(WindowInsets.statusBars)`. Remove the `no-featured`
status-bar/chrome spacer and replace it with:

```kotlin
item(key = "no-featured") {
    Spacer(modifier = Modifier.height(16.dp))
}
```

In Browse, change the outer modifier to:

```kotlin
modifier = Modifier.fillMaxSize()
```

In Collections, remove `contentTopPadding` and every
`.padding(top = contentTopPadding)` while retaining existing grid/content
padding and `LocalBottomChromeInset`.

- [ ] **Step 5: Simplify the carousel's top inset**

In `FeaturedCarousel`, replace `extraTopInset` and the system-inset calculation:

```kotlin
topInset: androidx.compose.ui.unit.Dp = 16.dp,
```

and:

```kotlin
Spacer(modifier = Modifier.height(topInset))
```

Remove the unused `WindowInsets`, `asPaddingValues`, and
`calculateTopPadding` imports. The only caller uses the 16dp default.

- [ ] **Step 6: Run the focused test and existing phone metadata/menu regressions**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests org.siloserver.silo.android.ui.screens.libraries.LibraryChromeInsetSourceTest \
  --tests org.siloserver.silo.android.ui.screens.home.FeaturedHeroMetadataTest \
  --tests org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherMenuEntrySourceTest \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: BUILD SUCCESSFUL; all selected tests pass.

- [ ] **Step 7: Inspect the scoped diff and commit**

Run:

```bash
git diff --check
git diff -- \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibraryChromeInsetSourceTest.kt
```

Commit:

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibraryChromeInsetSourceTest.kt
git commit -m "fix(android): keep library content below chrome"
```

### Task 2: Verify the integrated branch and publish tester artifacts

**Files:**
- Verify only: all files changed by `origin/main...HEAD`

**Interfaces:**
- Consumes: Task 1's fixed library viewport plus existing TV focus, editorial hero, and identity-scoped request fixes.
- Produces: independently reviewed branch, green phone/TV gates, signed universal tester APKs, and updated PR #126.

- [ ] **Step 1: Run supply-chain policy**

Run:

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Expected: self-tests pass and both commands exit 0.

- [ ] **Step 2: Run the full phone/TV unit and release gate**

Run:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: BUILD SUCCESSFUL; no unit-test failures; both universal release APKs exist.

- [ ] **Step 3: Perform emulator-only visual validation when available**

Use only a dedicated phone emulator selected explicitly through the local test
harness. Install the debug build serial-specifically, launch the
phone app, and verify Recommended, Browse, and Collections content stops at the
fixed chrome boundary before and after scrolling. Open/close the profile menu
and verify the list position does not change. If the dedicated emulator is not
online, record the limitation; do not touch a physical device.

- [ ] **Step 4: Obtain independent whole-branch review**

Provide the reviewer `origin/main...HEAD`, the approved specs, focused/full test
results, and emulator evidence or limitation. Require explicit approval or fix
each verified finding test-first before publication.

- [ ] **Step 5: Verify and copy signed universal APKs**

Verify package/version, universal ABIs, v2 signature, size, and SHA-256 for:

```text
androidApp/build/outputs/apk/release/androidApp-universal-release.apk
androidTvApp/build/outputs/apk/release/androidTvApp-universal-release.apk
```

Copy them without overwriting existing files into the tester-selected release
artifact destination outside the repository, using filenames containing
`0.3.11`, `FocusHeroLibraryInset`, and the final short commit.

- [ ] **Step 6: Final hygiene and PR update**

Run:

```bash
git diff --check origin/main...HEAD
git status --short --branch
git log --oneline origin/main..HEAD
```

Push `fix/tv-for-you-cold-navigation`, update PR #126 with the final test,
review, emulator, and artifact evidence, and leave it open and unmerged.
