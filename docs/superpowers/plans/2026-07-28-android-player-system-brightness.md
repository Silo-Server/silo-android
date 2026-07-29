# Android Player System Brightness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the Android phone player's window-brightness override while preserving every other player gesture and keep-screen-awake behavior.

**Architecture:** Extract the vertical-drag start-zone decision into a small pure classifier used by `PlayerGestureHandler`. The classifier maps the left edge to no action, the right edge to volume, and the center to dismissal; the obsolete brightness mode and window mutation are then removed.

**Tech Stack:** Kotlin, Jetpack Compose pointer input, Android `AudioManager`, Kotlin/JUnit unit tests, Gradle.

## Global Constraints

- Android phone only; do not change Android TV.
- Never write `WindowManager.LayoutParams.screenBrightness`.
- Preserve right-edge volume, center swipe-down dismissal, double-tap seeking, pinch aspect changes, control toggling, temporary fast-forward, and `FLAG_KEEP_SCREEN_ON`.
- A left-edge vertical drag is a no-op and must not become a dismiss candidate.
- Do not add permissions or mutate Android system brightness settings.
- Do not install or modify the Shield.

---

### Task 1: Remove mobile window-brightness ownership

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerGestureHandler.kt`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerVerticalDragModeTest.kt`

**Interfaces:**
- Produces: `internal enum class VerticalDragMode { None, Volume, DismissCandidate }`.
- Produces: `internal fun verticalDragMode(startX: Float, width: Float, edgeZonePx: Float): VerticalDragMode`.
- Preserves: `adjustVolume(AudioManager, Float)` and every public `PlayerGestureHandler` parameter.

- [ ] **Step 1: Write the failing classifier tests**

```kotlin
class PlayerVerticalDragModeTest {
    @Test
    fun `left edge leaves system brightness authoritative`() {
        assertEquals(
            VerticalDragMode.None,
            verticalDragMode(startX = 40f, width = 1_000f, edgeZonePx = 88f),
        )
    }

    @Test
    fun `right edge retains volume routing`() {
        assertEquals(
            VerticalDragMode.Volume,
            verticalDragMode(startX = 950f, width = 1_000f, edgeZonePx = 88f),
        )
    }

    @Test
    fun `center retains dismiss routing`() {
        assertEquals(
            VerticalDragMode.DismissCandidate,
            verticalDragMode(startX = 500f, width = 1_000f, edgeZonePx = 88f),
        )
    }
}
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests '*PlayerVerticalDragModeTest*' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: test compilation fails because the production classifier and visible
mode contract do not exist yet.

- [ ] **Step 3: Implement the minimal routing change**

In `PlayerGestureHandler.kt`, add:

```kotlin
internal enum class VerticalDragMode { None, Volume, DismissCandidate }

internal fun verticalDragMode(
    startX: Float,
    width: Float,
    edgeZonePx: Float,
): VerticalDragMode = when {
    startX < edgeZonePx -> VerticalDragMode.None
    startX > width - edgeZonePx -> VerticalDragMode.Volume
    else -> VerticalDragMode.DismissCandidate
}
```

Use this function from `onDragStart`. Remove `VerticalDragMode.Brightness`,
`adjustBrightness`, and the unused `Window`/`WindowManager` imports. Keep
`LocalContext` because `AudioManager` still needs it. Update the gesture
documentation to say the left edge is reserved and does not alter brightness.

- [ ] **Step 4: Run GREEN**

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests '*PlayerVerticalDragModeTest*' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: all three routing tests pass.

- [ ] **Step 5: Run focused player regressions**

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests '*Player*Gesture*' \
  --tests '*PlayerPinchGravity*' \
  --tests '*MobilePlayerLifecycle*' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 6: Build the phone release**

```bash
./gradlew :androidApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2 --no-daemon
```

Expected: `BUILD SUCCESSFUL` and release APK outputs exist.

- [ ] **Step 7: Verify the final diff and commit**

```bash
git diff --check
git diff --stat
git status --short
```

Confirm that no Android TV or system-settings code changed, then commit:

```bash
git add \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerGestureHandler.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerVerticalDragModeTest.kt
git commit -m "fix(player): leave phone brightness system-managed"
```

### Task 2: Pixel validation and PR update

**Files:**
- Modify only if a confirmed regression requires a test-first correction: Task 1 files.
- Update: PR #127 description/check evidence without merging.

**Interfaces:**
- Consumes: the Task 1 release APK.
- Produces: Pixel evidence that system brightness remains authoritative and preserved gestures still operate.

- [ ] **Step 1: Verify safe Pixel upgrade compatibility**

On serial `58211FDCQ000CU`, compare candidate and installed package, version,
and signing certificate. Abort without uninstall, clear-data, or downgrade if
they differ incompatibly.

- [ ] **Step 2: Install and launch on the Pixel**

```bash
adb -s 58211FDCQ000CU install -r \
  androidApp/build/outputs/apk/release/androidApp-arm64-v8a-release.apk
adb -s 58211FDCQ000CU shell am start \
  -n org.siloserver.silo/.android.MainActivity
```

- [ ] **Step 3: Validate behavior**

During video playback, verify:

1. Android's brightness slider changes display brightness before and after a
   left-edge vertical drag.
2. A left-edge drag does not dismiss playback.
3. A right-edge drag still changes media volume.
4. A center downward drag still dismisses after playback is established.
5. Double-tap seek and pinch aspect-mode changes still work.
6. Playback still prevents the display from sleeping while playing/buffering.
7. Fresh app logs contain no fatal exception, crash, or ANR.

- [ ] **Step 4: Push and update PR #127**

Push `fix/subtitle-aspect-recenter`, record focused test/release/Pixel evidence
on PR #127, and wait for hosted Unit tests and CodeRabbit. Do not merge without
fresh user authorization.
