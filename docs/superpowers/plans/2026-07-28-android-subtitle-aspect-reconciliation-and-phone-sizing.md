# Android Subtitle Aspect Reconciliation and Phone Sizing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate stale/clipped subtitle geometry after Android aspect changes and make all phone subtitle presets 1.125× larger without changing TV sizing.

**Architecture:** `SubtitleManager` remains the shared owner of Media3 and libass subtitle presentation. A fixed phone/television presentation class selects the font fraction, while `SubtitleVideoRectSync` uses generation-bound post-layout snapshot verification to request at most one corrective pre-draw pass when an aspect change exposes stale `exo_content_frame` bounds.

**Tech Stack:** Kotlin Multiplatform, Android Media3 `PlayerView`/`SubtitleView`, Compose `AndroidView`, Koin, Robolectric, Gradle.

## Global Constraints

- Preserve subtitle selection, cue styling, authored positioning, libass/ASS, bitmap subtitle, letterbox, and title-safe behavior.
- Do not change subtitle tracks, server subtitle processing, playback protocols, preset names, or persisted subtitle appearance schema.
- Phone fractions are exactly Small `22.5 / 720`, Medium `29.25 / 720`, Large `36 / 720`, XLarge `45 / 720`, and XXLarge `54 / 720`.
- Television fractions remain Small `20 / 720`, Medium `26 / 720`, Large `32 / 720`, XLarge `40 / 720`, and XXLarge `48 / 720`.
- Reconciliation is bounded to two post-layout applications per explicit sync generation, coalesces repeated requests, and cancels all pending work on detach/dispose.
- Use Media3's measured `exo_content_frame`; do not duplicate Media3's aspect-ratio algorithm.
- Do not install or modify the Shield. Physical validation is limited to Pixel serial `58211FDCQ000CU`.

---

### Task 1: Phone-only subtitle preset scaling

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt:40-46,334-342`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt:35-55`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/di/AndroidModule.kt:219-222`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/di/AndroidTvModule.kt:142-146`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/SubtitleAspectSyncWiringTest.kt`
- Test: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleAspectSyncWiringTest.kt`

**Interfaces:**
- Produces: `enum class AndroidSubtitlePresentation { Phone, Television }`.
- Produces: `SubtitleManager(libassBridge: LibassBridge? = null, presentation: AndroidSubtitlePresentation = AndroidSubtitlePresentation.Television)`.
- Preserves: all existing `SubtitleManager()` test and utility construction as television-scale compatibility.

- [ ] **Step 1: Write failing fraction tests**

Replace the single reflected web-scale test with explicit phone and television assertions:

```kotlin
@Test
fun phoneSubtitleTextFractionsUseApprovedPhoneScale() {
    val manager = SubtitleManager(
        presentation = AndroidSubtitlePresentation.Phone,
    )
    assertEquals(22.5f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Small))
    assertEquals(29.25f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Medium))
    assertEquals(36f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Large))
    assertEquals(45f / 720f, fractionalSize(manager, SubtitleFontSizePreset.XLarge))
    assertEquals(54f / 720f, fractionalSize(manager, SubtitleFontSizePreset.XXLarge))
}

@Test
fun televisionSubtitleTextFractionsPreserveExistingScale() {
    val manager = SubtitleManager(
        presentation = AndroidSubtitlePresentation.Television,
    )
    assertEquals(20f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Small))
    assertEquals(26f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Medium))
    assertEquals(32f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Large))
    assertEquals(40f / 720f, fractionalSize(manager, SubtitleFontSizePreset.XLarge))
    assertEquals(48f / 720f, fractionalSize(manager, SubtitleFontSizePreset.XXLarge))
}
```

Update the existing phone/TV source-wiring contract tests to require the named
`presentation` argument with `Phone` and `Television`, respectively. The
production change that makes these tests pass is explicit DI selection plus
the presentation-aware conversion.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*SubtitleManagerAppearanceTest*' \
  --tests '*SubtitleAspectSyncWiringTest*' \
  --tests '*TvSubtitleAspectSyncWiringTest*' \
  --max-workers=2 --no-daemon
```

Expected: compilation/assertion failures because
`AndroidSubtitlePresentation` and the explicit DI arguments do not exist and
phone fractions still equal television fractions.

- [ ] **Step 3: Implement the presentation-aware conversion**

Add the enum next to `SubtitleManager`, retain television as the default for
existing shared call sites, and select the numerator table without changing
the persisted `SubtitleFontSizePreset`:

```kotlin
enum class AndroidSubtitlePresentation {
    Phone,
    Television,
}

class SubtitleManager(
    private val libassBridge: LibassBridge? = null,
    private val presentation: AndroidSubtitlePresentation =
        AndroidSubtitlePresentation.Television,
) {
    private fun fractionalSizeFor(preset: SubtitleFontSizePreset): Float {
        val numerator = when (presentation) {
            AndroidSubtitlePresentation.Phone -> when (preset) {
                SubtitleFontSizePreset.Small -> 22.5f
                SubtitleFontSizePreset.Medium -> 29.25f
                SubtitleFontSizePreset.Large -> 36f
                SubtitleFontSizePreset.XLarge -> 45f
                SubtitleFontSizePreset.XXLarge -> 54f
            }
            AndroidSubtitlePresentation.Television -> when (preset) {
                SubtitleFontSizePreset.Small -> 20f
                SubtitleFontSizePreset.Medium -> 26f
                SubtitleFontSizePreset.Large -> 32f
                SubtitleFontSizePreset.XLarge -> 40f
                SubtitleFontSizePreset.XXLarge -> 48f
            }
        }
        return numerator / 720f
    }
}
```

Construct the phone singleton with
`presentation = AndroidSubtitlePresentation.Phone` and TV with
`presentation = AndroidSubtitlePresentation.Television`; use named arguments
for both constructor parameters.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass with no compilation
or assertion failure.

- [ ] **Step 5: Commit the sizing change**

```bash
git add \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/di/AndroidModule.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/di/AndroidTvModule.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/SubtitleAspectSyncWiringTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleAspectSyncWiringTest.kt
git commit -m "fix(subtitles): scale phone caption presets"
```

### Task 2: Bounded stale-frame convergence

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt:583-768`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt:360-560`

**Interfaces:**
- Consumes: existing `SubtitleVideoRectSync.updateAndReconcileAfterLayout()`.
- Produces: an internal immutable content-frame/resize snapshot used only to decide whether one corrective pre-draw is required.
- Preserves: `syncSubtitleVideoBounds(PlayerView)`, layout-listener behavior, and all public subtitle APIs.

- [ ] **Step 1: Add a production-shaped RED transition test**

Extend `MountedSubtitleCanvas` with a method that deliberately dispatches the
first pre-draw while the old content-frame bounds are still mounted, changes
the frame, drains the posted snapshot verification, then dispatches the
corrective pre-draw:

```kotlin
fun transitionAfterEarlyPreDraw(resizeMode: Int, finalFrame: FrameBounds) {
    schedule(resizeMode)
    playerView.viewTreeObserver.dispatchOnPreDraw()
    contentFrame.layout(
        finalFrame.left,
        finalFrame.top,
        finalFrame.right,
        finalFrame.bottom,
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    playerView.viewTreeObserver.dispatchOnPreDraw()
}
```

Add:

```kotlin
@Test
fun mountedCanvasCorrectsFillToFitWhenFirstPreDrawSeesCroppedFrame() {
    val canvas = MountedSubtitleCanvas()
    canvas.transition(
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        frame = FrameBounds(-120, -64, 2040, 1080),
    )

    canvas.transitionAfterEarlyPreDraw(
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        finalFrame = FrameBounds(240, 0, 1680, 1016),
    )

    assertEquals(SubtitleVideoRect(0, 0, 1440, 1016), canvas.subtitleRect())
}
```

The production change that makes this test pass is retaining the explicit-sync
generation long enough to notice that the frame snapshot changed after the
first pre-draw and scheduling exactly one corrective pass.

- [ ] **Step 2: Run the single test and verify RED**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests '*SubtitleManagerAppearanceTest.mountedCanvasCorrectsFillToFitWhenFirstPreDrawSeesCroppedFrame' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: FAIL because the existing one-shot pre-draw listener is removed
before the final Fit frame is mounted, leaving the Zoom-derived top/left
offset.

- [ ] **Step 3: Add latest-generation and detach RED tests**

Add tests that:

```kotlin
@Test
fun rapidEarlyTransitionsApplyOnlyLatestMode() {
    val canvas = MountedSubtitleCanvas()
    canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_FILL)
    canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    canvas.dispatchEarlyPreDrawThenMount(FrameBounds(240, 0, 1680, 1016))
    assertEquals(SubtitleVideoRect(0, 0, 1440, 1016), canvas.subtitleRect())
    assertEquals(2, canvas.reconciliationCount)
}

@Test
fun detachCancelsPostedSnapshotVerification() {
    val canvas = MountedSubtitleCanvas()
    canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    canvas.dispatchPreDraw()
    canvas.detach()
    canvas.mountFrameAndDrain(FrameBounds(-120, -64, 2040, 1080))
    assertEquals(1, canvas.reconciliationCount)
}
```

Expose `reconciliationCount` through the existing
`postLayoutReconciliationObserver`; keep all harness helpers test-only.

- [ ] **Step 4: Run the three tests and verify RED**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests '*SubtitleManagerAppearanceTest.mountedCanvasCorrectsFillToFitWhenFirstPreDrawSeesCroppedFrame' \
  --tests '*SubtitleManagerAppearanceTest.rapidEarlyTransitionsApplyOnlyLatestMode' \
  --tests '*SubtitleManagerAppearanceTest.detachCancelsPostedSnapshotVerification' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: the Fill-to-Fit test remains red and the new lifecycle/count
assertions fail because no generation-bound snapshot verification exists.

- [ ] **Step 5: Implement bounded snapshot verification**

Inside `SubtitleVideoRectSync`, add:

```kotlin
private data class LayoutSnapshot(
    val resizeMode: Int,
    val playerWidth: Int,
    val playerHeight: Int,
    val frameLeft: Int,
    val frameTop: Int,
    val frameWidth: Int,
    val frameHeight: Int,
)

private var reconciliationGeneration = 0L
private var pendingVerification: Runnable? = null
private var appliedPasses = 0
```

`updateAndReconcileAfterLayout()` increments the generation only when creating
a new explicit reconciliation request, resets `appliedPasses`, coalesces the
single pending pre-draw, and captures no mutable view geometry.

After the pre-draw calls `update()`, capture the snapshot actually applied,
increment `appliedPasses`, and post one main-thread verification runnable. The
runnable must:

```kotlin
if (
    !isDisposed &&
    generation == reconciliationGeneration &&
    appliedPasses < 2 &&
    currentSnapshot(playerView) != appliedSnapshot
) {
    schedulePreDrawFor(generation)
}
```

The second application does not post another correction. `dispose()` removes
the pre-draw listener, removes the posted runnable with
`playerView.removeCallbacks`, increments/invalidates the generation, and keeps
the existing listener cleanup. The permanent content-frame layout listener
continues to handle genuine later layouts.

- [ ] **Step 6: Run focused reconciliation tests and verify GREEN**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests '*SubtitleManagerAppearanceTest*' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: all appearance, geometry, coalescing, rapid-transition, and detach
tests pass. `repeatedExplicitSyncsRunOnePostLayoutReconciliation` must remain
green for stable geometry.

- [ ] **Step 7: Commit the reconciliation fix**

```bash
git add \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt
git commit -m "fix(subtitles): converge aspect bounds after layout"
```

### Task 3: Stabilize initial phone subtitle restore

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`
- Verify unchanged: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleRemountReselection.kt`
- Test: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/SubtitleRemountReselectionTest.kt`

**Interfaces:**
- Consumes: `MobileSubtitleTransactionAdapter.reportMountedSelection(...)` and
  the existing five-second mobile mount deadline.
- Produces: stable-snapshot evidence owned by the pending mobile mount
  generation; the first non-empty miss remains pending, a changed snapshot
  restarts settlement, and a repeated identical miss may fail.

- [ ] **Step 1: Write the failing mobile transaction tests**

Change the immediate-miss test so it reports one ready, non-empty catalog and
asserts that the pending local restore remains active with no failure. Add a
second test that reports the same key twice and asserts the existing failure,
plus a changed-key test that requires the changed key to repeat before failure.

```kotlin
harness.adapter.reportMountedSelection(
    identity = local,
    selected = false,
    snapshotKey = "intermediate",
    settled = true,
)
assertEquals(local, harness.adapter.snapshot.localMountIdentity)
assertNull(harness.adapter.snapshot.failureMessage)

harness.adapter.reportMountedSelection(
    identity = local,
    selected = false,
    snapshotKey = "intermediate",
    settled = true,
)
assertNull(harness.adapter.snapshot.localMountIdentity)
assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", true) == true)
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests '*MobileSubtitleTransactionAdapterTest*' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: the first-snapshot assertion fails because the adapter currently
calls `failLocalMount` immediately.

- [ ] **Step 3: Implement generation-owned snapshot stabilization**

Add one nullable last-miss snapshot key to
`MobileSubtitleTransactionAdapter`. For a non-selected result with
`settled=true`, record the first non-blank key; fail only when the same key is
reported again. A changed key replaces the candidate and remains provisional.
Clear the candidate from `invalidateLocalMount()` so content, identity, and
generation changes cannot inherit old evidence.

In `PlayerScreen` keep the immediate `LaunchedEffect` mount attempt
provisional (`settled = false`). Track callbacks remain the source of settled
catalog evidence. Do not change the five-second timeout, successful-selection
path, persisted identity, or error copy.

- [ ] **Step 4: Run GREEN and focused TV parity**

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*MobileSubtitleTransactionAdapterTest*' \
  --tests '*SubtitleRemountReselectionTest*' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: mobile first/changed/repeated snapshot tests pass, and TV's existing
first-snapshot stabilization tests remain green without TV production edits.

- [ ] **Step 5: Commit**

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt
git commit -m "fix(subtitles): await stable tracks on playback restore"
```

### Task 4: Regression, release, and Pixel validation

**Files:**
- Modify only if evidence requires a test correction: files from Tasks 1-3.
- Record verification in the PR description; do not add generated artifacts to git.

**Interfaces:**
- Consumes: phone presentation scaling and bounded reconciliation from Tasks 1-2.
- Produces: verified phone/TV release artifacts and physical Pixel evidence.

- [ ] **Step 1: Run focused shared/phone/TV tests uncached**

```bash
./gradlew \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  --tests '*Subtitle*' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: all selected subtitle tests pass.

- [ ] **Step 2: Run supply-chain verification**

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Expected: both scripts exit zero without changing tracked files.

- [ ] **Step 3: Run the full unit and release gates**

```bash
./gradlew \
  testDebugUnitTest \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2 --no-daemon
```

Expected: `BUILD SUCCESSFUL`; both release APK outputs exist. Do not install the
TV artifact.

- [ ] **Step 4: Safely install the phone release on the Pixel**

Verify serial, package, version, and signer compatibility first. Then use only:

```bash
adb -s 58211FDCQ000CU install -r \
  androidApp/build/outputs/apk/release/androidApp-universal-release.apk
```

Abort without uninstalling, clearing data, downgrading, or changing settings if
the signer or version is incompatible.

- [ ] **Step 5: Validate the reproduced matrix on Pixel**

Using the already configured subtitle track:

1. Play through at least three consecutive text cues in Fit.
2. Change Fit → Fill → Stretch → Fit, closing the sheet after each selection.
3. Repeat Fill → Fit rapidly three times.
4. Confirm each cue is horizontally centered, fully above the display bottom,
   and visible on the first cue after each transition.
5. Confirm multi-line cues are not clipped.
6. Confirm the default Large phone size is visibly larger than the pre-fix
   `32 / 720` build and that Small through XXLarge remain ordered.
7. Capture serial-scoped screenshots and fresh app-process logs; confirm no
   fatal exception, ANR, subtitle parser error, or playback regression.

- [ ] **Step 6: Request independent review**

Provide the reviewer with the approved spec, this plan, commits from Tasks 1-2,
the focused/full gate outputs, and Pixel screenshots. Require explicit verdicts
on:

- generation/coalescing correctness,
- detach and callback ownership,
- parent-local Media3 geometry,
- phone-only sizing and TV preservation,
- absence of server/protocol/persistence changes.

Fix only evidenced findings test-first and rerun the smallest affected gate.

- [ ] **Step 7: Final diff and branch verification**

```bash
git diff --check origin/main...HEAD
git status --short
git log --oneline --decorate origin/main..HEAD
```

Expected: no whitespace errors, a clean worktree, and only the approved PR #127
subtitle work plus its spec/plan/fix commits.

- [ ] **Step 8: Push and update PR #127 without merging**

Push `fix/subtitle-aspect-recenter`, update PR #127 with the new Pixel
reproduction and verification evidence, and wait for hosted checks and
CodeRabbit. Do not merge without fresh user authorization.
