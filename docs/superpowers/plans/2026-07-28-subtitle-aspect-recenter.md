# Subtitle Aspect-Mode Recentring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the Android phone and TV subtitle canvas aligned with the final visible video viewport when switching among Fit, Fill/Zoom, and Stretch.

**Architecture:** `SubtitleManager` remains the only subtitle-geometry owner. A
pure mode-aware selector rejects stale fitted content-frame geometry while
preserving a matching post-layout content-frame rectangle so Zoom retains the
visible viewport's parent-local offset. The existing per-`PlayerView`
synchronizer performs one lifecycle-owned pre-draw reconciliation after each
explicit sync request and removes that observer on completion or disposal.

**Tech Stack:** Kotlin 2.1, Android Views, Media3 `PlayerView`/`AspectRatioFrameLayout`, Robolectric/JUnit, Gradle 8.12.

## Global Constraints

- Apply the shared correction to Android phone and Android TV; phone is the confirmed reproduction.
- Fit aligns the subtitle canvas with the fitted video rectangle.
- Phone Fill/Media3 Zoom and phone Stretch/Media3 Fill align the canvas with the full visible player viewport.
- Preserve authored ASS/SSA and PGS positions relative to the canvas; do not rewrite individual cue coordinates.
- Preserve existing letterbox detection, title-safe insets, subtitle appearance, timing, track selection, playback state, networking, and persisted settings.
- Do not add polling, arbitrary delays, a second renderer, server changes, protocol changes, or transcoding changes.
- A delayed reconciliation must not mutate a detached or replaced `PlayerView`.
- Do not install on the Shield without a separate explicit request.

---

### Task 1: Make subtitle canvas selection resize-mode aware

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt:445-492,662-673`
- Test: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt:143-251`

**Interfaces:**
- Consumes: `SubtitleVideoRect`, Media3 resize-mode constants, `displayedSubtitleVideoRect(...)`, and the current content-frame rectangle.
- Produces: `internal fun selectSubtitleCanvasRect(resizeMode: Int, contentFrameRect: SubtitleVideoRect?, displayedVideoRect: SubtitleVideoRect): SubtitleVideoRect`.

- [x] **Step 1: Add failing stale-frame regression tests**

Add these tests to `SubtitleManagerAppearanceTest`:

```kotlin
@Test
fun zoomIgnoresStaleFittedContentFrameAndUsesFullViewport() {
    val staleFit = SubtitleVideoRect(left = 0, top = 236, width = 2404, height = 1352)
    val fullViewport = SubtitleVideoRect(left = 0, top = 0, width = 2404, height = 1080)

    assertEquals(
        fullViewport,
        selectSubtitleCanvasRect(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            contentFrameRect = staleFit,
            displayedVideoRect = fullViewport,
        ),
    )
}

@Test
fun stretchIgnoresStaleFittedContentFrameAndUsesFullViewport() {
    val staleFit = SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080)
    val fullViewport = SubtitleVideoRect(left = 0, top = 0, width = 2400, height = 1080)

    assertEquals(
        fullViewport,
        selectSubtitleCanvasRect(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
            contentFrameRect = staleFit,
            displayedVideoRect = fullViewport,
        ),
    )
}

@Test
fun fitContinuesToUsePostLayoutContentFrame() {
    val fittedFrame = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)
    val computedFallback = SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080)

    assertEquals(
        fittedFrame,
        selectSubtitleCanvasRect(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            contentFrameRect = fittedFrame,
            displayedVideoRect = computedFallback,
        ),
    )
}

@Test
fun repeatedModeSelectionDoesNotRetainPreviousCanvas() {
    val fit = SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080)
    val full = SubtitleVideoRect(left = 0, top = 0, width = 2400, height = 1080)

    val fill = selectSubtitleCanvasRect(
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        fit,
        full,
    )
    val stretch = selectSubtitleCanvasRect(
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        fit,
        full,
    )
    val restoredFit = selectSubtitleCanvasRect(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        fit,
        fit,
    )

    assertEquals(full, fill)
    assertEquals(full, stretch)
    assertEquals(fit, restoredFit)
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests org.siloserver.silo.common.player.SubtitleManagerAppearanceTest \
  --max-workers=2 --no-daemon
```

Expected: compilation fails because `selectSubtitleCanvasRect` does not exist.

- [x] **Step 3: Implement the minimal mode-aware selector**

Add beside `displayedSubtitleVideoRect`:

```kotlin
internal fun selectSubtitleCanvasRect(
    resizeMode: Int,
    contentFrameRect: SubtitleVideoRect?,
    displayedVideoRect: SubtitleVideoRect,
): SubtitleVideoRect = when (resizeMode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    AspectRatioFrameLayout.RESIZE_MODE_FILL,
    -> contentFrameRect?.takeIf {
        it.width == displayedVideoRect.width &&
            it.height == displayedVideoRect.height
    } ?: displayedVideoRect
    else -> contentFrameRect ?: displayedVideoRect
}
```

Change `SubtitleVideoRectSync.applyRect` to compute both inputs before applying
letterbox and title-safe insets:

```kotlin
val resizeMode = playerView.resizeMode
val displayedVideoRect = displayedSubtitleVideoRect(
    viewWidth = playerView.width,
    viewHeight = playerView.height,
    videoWidth = videoSize.width,
    videoHeight = videoSize.height,
    videoPixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
    resizeMode = resizeMode,
)
val rect = selectSubtitleCanvasRect(
    resizeMode = resizeMode,
    contentFrameRect = playerView.contentFrameSubtitleRect(),
    displayedVideoRect = displayedVideoRect,
).insetByLetterbox(letterbox).insetByTitleSafe(titleSafeFraction)
```

For Zoom and Fill, a content-frame rectangle is used only when its dimensions
match the visible viewport. This preserves the post-layout parent-local offset
of an oversized, negatively positioned Zoom frame. A stale fitted rectangle
does not match, so selection falls back to `displayedVideoRect`.

- [x] **Step 4: Run the focused class and verify GREEN**

Run the Step 2 command.

Expected: `SubtitleManagerAppearanceTest` passes with zero failures.

- [x] **Step 5: Commit the independently testable geometry correction**

```bash
git add \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt
git commit -m "fix(subtitles): recenter canvas for fill modes"
```

---

### Task 2: Reconcile once after layout and cancel stale callbacks

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt:270-282,563-704`
- Test: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt`

**Interfaces:**
- Consumes: Task 1's `selectSubtitleCanvasRect(...)`.
- Produces: `SubtitleVideoRectSync.updateAndReconcileAfterLayout()`; at most one
  pre-draw observer per `PlayerView`, removed after execution or during disposal.

- [x] **Step 1: Add failing lifecycle regression tests**

Add Robolectric tests that mount a real `PlayerView` in an `Activity`, invoke
`SubtitleManager.syncSubtitleVideoBounds`, drive layout and pre-draw, and assert
the actual `SubtitleView` layout parameters for Fit → Zoom, Fit → Fill,
repeated switching, and Zoom → Fit. Count completed reconciliations so deleting
the coalescing guard fails the suite, and use sentinel layout parameters to
prove a detached view cannot be mutated by a pending observer:

```kotlin
@Test
fun repeatedExplicitSyncsRunOnePostLayoutReconciliation() {
    val mounted = MountedSubtitleCanvas()
    var reconciliations = 0
    mounted.manager.postLayoutReconciliationObserver = { reconciliations++ }

    repeat(5) {
        mounted.manager.syncSubtitleVideoBounds(mounted.playerView)
    }
    mounted.dispatchPreDraw()

    assertEquals(1, reconciliations)
}

@Test
fun detachCancelsPendingPostLayoutReconciliationWithoutMutatingLayout() {
    val mounted = MountedSubtitleCanvas()
    val sentinel = FrameLayout.LayoutParams(17, 19)
    mounted.subtitleView.layoutParams = sentinel

    mounted.detach()
    mounted.dispatchPreDraw()

    assertSame(sentinel, mounted.subtitleView.layoutParams)
}
```

The execution observer is instance-local, internal, and null by default. It
adds only a null check in production and does not retain a `PlayerView`.

- [x] **Step 2: Run the focused class and verify RED**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests org.siloserver.silo.common.player.SubtitleManagerAppearanceTest \
  --max-workers=2 --no-daemon
```

Expected: mounted transition assertions fail before the content-frame offset
and lifecycle-owned post-layout reconciliation are implemented.

- [x] **Step 3: Implement one lifecycle-owned post-layout callback**

In `SubtitleVideoRectSync`, register one removable pre-draw observer:

```kotlin
private var pendingPreDrawObserver: ViewTreeObserver? = null
private val postLayoutUpdate = ViewTreeObserver.OnPreDrawListener {
    clearPendingPostLayoutUpdate()
    if (!isDisposed) {
        update()
        onPostLayoutReconciled()
    }
    true
}

fun updateAndReconcileAfterLayout() {
    update()
    val playerView = playerViewRef.get() ?: return
    if (isDisposed) return
    pendingPreDrawObserver?.let { observer ->
        if (observer.isAlive) return
        pendingPreDrawObserver = null
    }
    val observer = playerView.viewTreeObserver
    if (!observer.isAlive) return
    pendingPreDrawObserver = observer
    observer.addOnPreDrawListener(postLayoutUpdate)
}
```

Change `SubtitleManager.syncSubtitleVideoBounds` to call:

```kotlin
sync.updateAndReconcileAfterLayout()
```

In `dispose`, remove the observer before clearing listeners:

```kotlin
clearPendingPostLayoutUpdate()
```

`clearPendingPostLayoutUpdate()` removes the listener from the exact
`ViewTreeObserver` used for registration and clears the reference. Ordinary
layout/video-size callbacks continue calling `update()` directly, keeping the
extra reconciliation bounded to explicit screen sync requests.

- [x] **Step 4: Run focused tests and verify GREEN**

Run the Step 2 command.

Expected: all `SubtitleManagerAppearanceTest` tests pass.

- [x] **Step 5: Run neighboring subtitle geometry tests**

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests org.siloserver.silo.common.player.SubtitleManagerAppearanceTest \
  --tests org.siloserver.silo.common.player.LetterboxInsetTest \
  --tests org.siloserver.silo.common.player.TitleSafeInsetTest \
  --max-workers=2 --no-daemon
```

Expected: zero failures.

- [x] **Step 6: Commit the lifecycle correction**

```bash
git add \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt
git commit -m "fix(subtitles): reconcile canvas after aspect layout"
```

---

### Task 3: Lock phone/TV wiring and verify release behaviour

**Files:**
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/SubtitleAspectModeWiringSourceTest.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleAspectModeWiringSourceTest.kt`
- Verify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt:1085-1123`
- Verify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt:1775-1793,3088-3113`

**Interfaces:**
- Consumes: existing `SubtitleManager.syncSubtitleVideoBounds(PlayerView)`, phone resize-mode mapping, and TV `applyPlayerViewVideoFillMode`.
- Produces: platform source-contract tests ensuring each resize update is immediately followed by shared subtitle reconciliation.

- [x] **Step 1: Add phone and TV source-contract tests**

Phone:

```kotlin
class SubtitleAspectModeWiringSourceTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private fun playerViewUpdateBlock(source: String): String {
        val factoryIndex = source.indexOf("PlayerView(ctx).apply {")
        require(factoryIndex >= 0) { "PlayerView factory anchor is missing" }
        val androidViewIndex = source.lastIndexOf("AndroidView(", factoryIndex)
        require(androidViewIndex >= 0) { "Enclosing AndroidView is missing" }
        val updateIndex = source.indexOf("update = { view ->", factoryIndex)
        require(updateIndex > factoryIndex) {
            "PlayerView update lambda is missing or misordered"
        }
        val endIndex = source.indexOf("modifier = Modifier", updateIndex)
        require(endIndex > updateIndex) {
            "PlayerView update lambda terminator is missing or misordered"
        }
        return source.substring(updateIndex, endIndex)
    }

    @Test
    fun playerViewReconcilesSubtitlesAfterResizeModeUpdate() {
        val source = source(
            "org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt"
        )
        val update = playerViewUpdateBlock(source)

        assertTrue(update.contains("view.resizeMode = resizeMode"))
        assertTrue(update.contains("subtitleManager.syncSubtitleVideoBounds(view)"))
        assertTrue(
            update.indexOf("view.resizeMode = resizeMode") <
                update.indexOf("subtitleManager.syncSubtitleVideoBounds(view)")
        )
    }
}
```

TV:

```kotlin
class TvSubtitleAspectModeWiringSourceTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidTvApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private fun playerViewUpdateBlock(source: String): String {
        val factoryIndex = source.indexOf(") as PlayerView).apply {")
        require(factoryIndex >= 0) { "PlayerView factory anchor is missing" }
        val androidViewIndex = source.lastIndexOf("AndroidView(", factoryIndex)
        require(androidViewIndex >= 0) { "Enclosing AndroidView is missing" }
        val updateIndex = source.indexOf("update = { view ->", factoryIndex)
        require(updateIndex > factoryIndex) {
            "PlayerView update lambda is missing or misordered"
        }
        val endIndex = source.indexOf(
            "if (!isInPictureInPictureMode",
            updateIndex,
        )
        require(endIndex > updateIndex) {
            "PlayerView update lambda terminator is missing or misordered"
        }
        return source.substring(updateIndex, endIndex)
    }

    @Test
    fun playerViewReconcilesSubtitlesAfterFillModeUpdate() {
        val source = source(
            "org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt"
        )
        val update = playerViewUpdateBlock(source)

        val aspectCall = "applyPlayerViewVideoFillMode(view, state.videoFillMode)"
        val subtitleCall = "subtitleManager.syncSubtitleVideoBounds(view)"
        assertTrue(update.contains(aspectCall))
        assertTrue(update.contains(subtitleCall))
        assertTrue(update.indexOf(aspectCall) < update.indexOf(subtitleCall))
    }
}
```

Both files import `java.io.File`, `kotlin.test.Test`, and
`kotlin.test.assertTrue`.

- [x] **Step 2: Prove the source tests detect reversed ordering**

Temporarily reverse each extracted ordering assertion (`<` to `>`) and run:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*SubtitleAspectModeWiringSourceTest' \
  --max-workers=2 --no-daemon
```

Expected: both tests fail on their ordering assertion. Restore `<` before
continuing.

- [x] **Step 3: Run the source tests GREEN**

Run the Step 2 command after restoring the intended assertions.

Expected: both tests pass.

- [x] **Step 4: Run the complete relevant feature gate**

```bash
./gradlew \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*SubtitleManagerAppearanceTest' \
  --tests '*LetterboxInsetTest' \
  --tests '*TitleSafeInsetTest' \
  --tests '*SubtitleAspectModeWiringSourceTest' \
  --max-workers=2 --no-daemon
```

Expected: zero failures.

- [x] **Step 5: Run full debug unit tests**

```bash
./gradlew testDebugUnitTest --max-workers=2 --no-daemon
```

Expected: build succeeds with zero test failures.

- [x] **Step 6: Run supply-chain and release compilation gates**

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
./gradlew \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2 --no-daemon
```

Expected: policy scripts exit zero and both minified release assemblies succeed.

- [ ] **Step 7: Verify on the physical Pixel only — blocked: device disconnected**

First confirm serial `58211FDCQ000CU`, compare the candidate and installed
package/version/signing certificate, and stop if the signer differs. Then use
only:

```bash
adb -s 58211FDCQ000CU install -r \
  androidApp/build/outputs/apk/release/androidApp-universal-release.apk
adb -s 58211FDCQ000CU shell am start -W \
  -n org.siloserver.silo/org.siloserver.silo.android.MainActivity
```

With Bluetooth earbuds disconnected, play a title containing centred text
subtitles and switch Fit → Fill → Stretch → Fit. Capture screenshots after
layout settles and verify:

- Fill and Stretch centre the subtitle canvas in the full visible viewport.
- Returning to Fit restores the fitted-video canvas.
- repeated switching does not retain an earlier offset;
- subtitle timing and vertical position remain stable;
- no immediate fatal exception, ANR, or player error appears in Pixel logcat.

Do not issue any ADB command to the Shield or an emulator.

- [x] **Step 8: Request independent focused review**

Review only the branch diff against:

- mode-aware stale-frame rejection;
- authored cue preservation;
- bounded callback ownership and detach cancellation;
- phone/TV wiring;
- absence of unrelated playback changes.

Address every substantive finding test-first and rerun Tasks 1-3's focused
gates.

- [x] **Step 9: Commit verification contracts**

```bash
git add \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/SubtitleAspectModeWiringSourceTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleAspectModeWiringSourceTest.kt
git commit -m "test(subtitles): lock aspect recenter wiring"
```

- [x] **Step 10: Final diff and branch verification**

```bash
git diff --check origin/main...HEAD
git status --short
git log --oneline origin/main..HEAD
```

Expected: no whitespace errors, clean worktree, and only the approved spec,
plan, shared geometry fix, lifecycle reconciliation, and platform tests.
