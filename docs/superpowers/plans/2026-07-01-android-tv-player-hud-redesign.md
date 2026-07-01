# Android TV Player HUD Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the cramped fixed-height Android TV player HUD with an adaptive, readable, Android-specific overlay whose Info, Stats, Video, Audio, Subtitles, and Chapters tabs do not clip and feel coherent.

**Architecture:** Keep the HUD implementation localized to `TvPlayerHud.kt`. Introduce small layout constants and shared row primitives inside that file, then update focused source/unit tests to enforce adaptive sizing, scroll-safe pane content, and tab behavior. The player screen continues to open the same `TvPlayerHud`; no playback routing, engine, subtitle rendering, or bottom control changes are included.

**Tech Stack:** Kotlin, Jetpack Compose for TV, AndroidX Media3 state already exposed by `TvPlayerViewModel`, existing Kotlin/JVM unit and source tests via Gradle.

## Global Constraints

- Preserve all current HUD functionality: Info, Stats, Video, Audio, Subtitles, Chapters, pickers, subtitle search, AI translation, sleep timer, and chapter selection.
- Use tvOS as visual guidance, but allow Android-specific sizing and structure.
- Do not redesign the bottom playback controls in this pass.
- Do not remove Android-only subtitle search or AI translation controls.
- Do not introduce request, watch-together, or admin entry points into the player HUD.
- Do not change playback engine routing, subtitle rendering behavior, or stream selection logic.
- Keep changes localized to `TvPlayerHud.kt` plus focused tests.

---

### Task 1: Adaptive HUD Shell And Test Guards

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerHud.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerControlsUsabilityTest.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/theme/TvSkylineTokenParityTest.kt`

**Interfaces:**
- Consumes: existing `TvPlayerHud(...)` composable signature.
- Produces: internal layout constants `HudMaxWidth`, `HudMinHeight`, `HudMaxHeight`, `HudPanelCorner`, `HudContentGap`, and `HudPanelPadding`; existing callers remain unchanged.

- [ ] **Step 1: Write failing source-test expectations for adaptive shell**

Update `TvPlayerControlsUsabilityTest.hudIsFloatingTopCenterCardInsteadOfRightDrawer` so it rejects the fixed 190dp shell and expects adaptive bounds:

```kotlin
@Test
fun hudIsAdaptiveTopCenterCardInsteadOfRightDrawer() {
    assertTrue(screenSource.contains("Alignment.TopCenter"))
    assertTrue(hudSource.contains("private val HudMaxWidth = 720.dp"))
    assertTrue(hudSource.contains("private val HudMinHeight = 300.dp"))
    assertTrue(hudSource.contains(".heightIn(min = HudMinHeight, max = HudMaxHeight)"))
    assertFalse(hudSource.contains(".height(190.dp)"))
    assertFalse(hudSource.contains("PlayerSidePanel"))
    assertFalse(hudSource.contains(".width(560.dp)"))
}
```

Update `TvSkylineTokenParityTest.playerHudUsesHalfScaleTvOsShellAndPickerGeometry` so it documents Android-specific HUD sizing:

```kotlin
@Test
fun playerHudUsesAdaptiveAndroidTvShellAndPickerGeometry() {
    assertTrue(playerHud.contains("private val HudMaxWidth = 720.dp"))
    assertTrue(playerHud.contains("private val HudMinHeight = 300.dp"))
    assertTrue(playerHud.contains("private val HudMaxHeight = 430.dp"))
    assertTrue(playerHud.contains(".widthIn(max = HudMaxWidth)"))
    assertTrue(playerHud.contains(".heightIn(min = HudMinHeight, max = HudMaxHeight)"))
    assertTrue(playerHud.contains(".clip(RoundedCornerShape(HudPanelCorner))"))
    assertTrue(playerHud.contains("width = 0.5.dp"))
    assertTrue(playerHud.contains(".padding(HudPanelPadding)"))
    assertFalse(playerHud.contains(".widthIn(max = 550.dp)"))
    assertFalse(playerHud.contains(".height(190.dp)"))
    assertFalse(playerHud.contains(".widthIn(max = 1100.dp)"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'com.continuum.app.tv.ui.screens.player.TvPlayerControlsUsabilityTest' \
  --tests 'com.continuum.app.tv.ui.theme.TvSkylineTokenParityTest'
```

Expected: fails because `HudMaxWidth`, `HudMinHeight`, `HudMaxHeight`, and adaptive `heightIn(...)` do not exist and old `.height(190.dp)` still exists.

- [ ] **Step 3: Implement adaptive HUD shell constants**

Near the top of `TvPlayerHud.kt`, after imports and before the composable KDoc, add:

```kotlin
private val HudMaxWidth = 720.dp
private val HudMinHeight = 300.dp
private val HudMaxHeight = 430.dp
private val HudPanelCorner = 18.dp
private val HudPanelPadding = 18.dp
private val HudContentGap = 12.dp
private val HudTabHeight = 32.dp
```

Replace the shell modifier inside `TvPlayerHud`:

```kotlin
Box(
    modifier = modifier
        .widthIn(max = HudMaxWidth)
        .fillMaxWidth(0.8f)
        .heightIn(min = HudMinHeight, max = HudMaxHeight)
        .clip(RoundedCornerShape(HudPanelCorner))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
        .border(
            width = 0.5.dp,
            color = Color.White.copy(alpha = 0.14f),
            shape = RoundedCornerShape(HudPanelCorner),
        )
        .onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyUp &&
                (ev.key == Key.Back || ev.key == Key.Escape)
            ) {
                if (activePicker != null) {
                    activePicker = null
                } else {
                    onDismiss()
                }
                true
            } else {
                false
            }
        }
        .padding(HudPanelPadding),
) {
```

Change the HUD content column spacing:

```kotlin
verticalArrangement = Arrangement.spacedBy(HudContentGap),
```

Change `HudTabPill` height:

```kotlin
.height(HudTabHeight)
```

- [ ] **Step 4: Run tests to verify Task 1 passes**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'com.continuum.app.tv.ui.screens.player.TvPlayerControlsUsabilityTest' \
  --tests 'com.continuum.app.tv.ui.theme.TvSkylineTokenParityTest'
```

Expected: PASS for updated shell assertions. If unrelated source assertions fail because old compact typography is still expected, defer only those typography assertions to Task 2.

- [ ] **Step 5: Commit Task 1**

```bash
git add androidTvApp/src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerHud.kt \
  androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerControlsUsabilityTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/theme/TvSkylineTokenParityTest.kt
git commit -m "fix(tv): make player hud shell adaptive"
```

---

### Task 2: Pane Layouts, Rows, And Sleep Timer

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerHud.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerControlsUsabilityTest.kt`

**Interfaces:**
- Consumes: Task 1 HUD constants.
- Produces: reusable content helpers `HudPaneViewport`, `HudTwoColumnPane`, and an updated `HudVideoPane` where sleep timer is a row opening a picker.

- [ ] **Step 1: Write failing source-test expectations for non-clipping panes**

Add this test to `TvPlayerControlsUsabilityTest`:

```kotlin
@Test
fun hudTabsUseScrollSafePaneViewports() {
    assertTrue(hudSource.contains("private fun HudPaneViewport("))
    assertTrue(hudSource.contains("private fun HudTwoColumnPane("))
    assertTrue(hudSource.contains(".padding(bottom = HudPaneBottomPadding)"))
    assertTrue(hudSource.contains("HudTab.Info -> HudPaneViewport"))
    assertTrue(hudSource.contains("HudTab.Stats -> HudPaneViewport"))
    assertTrue(hudSource.contains("HudTab.Chapters -> HudPaneViewport"))
}
```

Add this test to replace the old chip-focused sleep timer expectations:

```kotlin
@Test
fun videoTabUsesSettingRowsForSleepTimer() {
    assertTrue(hudSource.contains("label = \"Sleep timer\""))
    assertTrue(hudSource.contains("title = \"Sleep Timer\""))
    assertTrue(hudSource.contains("HudPickerOption(\"cancel\", \"Cancel timer\")"))
    assertFalse(hudSource.contains("SLEEP_TIMER_PRESETS.forEach { minutes ->"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'com.continuum.app.tv.ui.screens.player.TvPlayerControlsUsabilityTest'
```

Expected: FAIL because `HudPaneViewport`, `HudTwoColumnPane`, `HudPaneBottomPadding`, and row-based sleep timer are not implemented.

- [ ] **Step 3: Add pane viewport helpers**

In `TvPlayerHud.kt`, add constants near Task 1 constants:

```kotlin
private val HudPaneBottomPadding = 12.dp
private val HudPaneColumnGap = 28.dp
```

Add helper composables before `HudInfoPane`:

```kotlin
@Composable
private fun HudPaneViewport(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = HudPaneBottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun HudTwoColumnPane(
    modifier: Modifier = Modifier,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = HudPaneBottomPadding),
        horizontalArrangement = Arrangement.spacedBy(HudPaneColumnGap),
    ) {
        PaneColumn("Title", modifier = Modifier.weight(1f), content = left)
        PaneColumn("Stream", modifier = Modifier.weight(1f), content = right)
    }
}
```

- [ ] **Step 4: Wrap tab content with scroll-safe viewports**

In the `when (selectedTab)` block, change Info to:

```kotlin
HudTab.Info -> HudPaneViewport {
    HudInfoPane(
        title = title,
        positionSec = positionSec,
        durationSec = durationSec,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        stats = stats,
        playbackPlan = playbackPlan,
        subtitleTracks = subtitleTracks,
        chapters = chapters,
    )
}
```

Change Stats to:

```kotlin
HudTab.Stats -> HudPaneViewport { HudStatsPane(stats) }
```

Change Chapters to:

```kotlin
HudTab.Chapters -> HudPaneViewport {
    HudChaptersPane(
        chapters = chapters,
        onSelectChapter = onSelectChapter,
    )
}
```

Then remove direct `.fillMaxSize().verticalScroll(...)` wrappers inside `HudInfoPane`, `HudStatsPane`, and `HudChaptersPane` if they produce nested scrolling. Keep each pane flat inside the viewport.

- [ ] **Step 5: Convert Info pane to a flat two-column pane**

Replace the root `Row` in `HudInfoPane` with `HudTwoColumnPane`:

```kotlin
HudTwoColumnPane(
    modifier = modifier,
    left = {
        Text(
            text = title.ifBlank { "Now Playing" },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 18.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (episodeTag != null) {
            Text(
                text = episodeTag,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            )
        }
        if (metaBits.isNotEmpty()) {
            Text(
                text = metaBits.joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            )
        }
    },
    right = {
        if (badges.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.forEach { badge -> HudBadge(badge) }
            }
        }
        streamRows.forEach { (label, value) ->
            LabelValueRow(label = label, value = value)
        }
    },
)
```

Create `HudBadge` by extracting the existing badge `Box`:

```kotlin
@Composable
private fun HudBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.35f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
```

- [ ] **Step 6: Convert sleep timer from chip row to setting row**

In `HudVideoPane`, replace the Timers column body with a `HudFocusedSettingRow`:

```kotlin
val activeSleep = sleepTimerState as? SleepTimerState.Active
HudFocusedSettingRow(
    label = "Sleep timer",
    value = activeSleep?.let { "Sleeping in ${formatSleepRemaining(it.remainingSeconds)}" } ?: "Off",
    enabled = enabled,
    onActivate = {
        val options = buildList {
            if (activeSleep != null) add(HudPickerOption("cancel", "Cancel timer"))
            add(HudPickerOption("off", "Off"))
            SLEEP_TIMER_PRESETS.forEach { minutes ->
                add(HudPickerOption(minutes.toString(), sleepPresetLabel(minutes)))
            }
        }
        onPresentPicker(
            HudPickerPresentation(
                title = "Sleep Timer",
                options = options,
                selectedId = activeSleep?.let { "active" } ?: "off",
                onSelect = { id ->
                    when (id) {
                        "cancel", "off" -> onCancelSleepTimer()
                        else -> id.toIntOrNull()?.let(onStartSleepTimer)
                    }
                },
            ),
        )
    },
)
```

- [ ] **Step 7: Run tests to verify Task 2 passes**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'com.continuum.app.tv.ui.screens.player.TvPlayerControlsUsabilityTest' \
  --tests 'com.continuum.app.tv.ui.screens.player.TvPlayerHudTabsTest'
```

Expected: PASS.

- [ ] **Step 8: Commit Task 2**

```bash
git add androidTvApp/src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerHud.kt \
  androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerControlsUsabilityTest.kt
git commit -m "fix(tv): improve player hud tab layouts"
```

---

### Task 3: Build, Install, And Visual QA Across Tabs

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerHud.kt` only if Task 3 visual QA finds small spacing bugs.
- Test: existing HUD-focused unit/source tests.

**Interfaces:**
- Consumes: Task 1 adaptive shell and Task 2 pane helpers.
- Produces: installed TV APK and screenshots showing no default HUD clipping.

- [ ] **Step 1: Run focused HUD and player tests**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'com.continuum.app.tv.ui.screens.player.TvPlayerControlsUsabilityTest' \
  --tests 'com.continuum.app.tv.ui.screens.player.TvPlayerHudTabsTest' \
  --tests 'com.continuum.app.tv.ui.theme.TvSkylineTokenParityTest'
```

Expected: PASS.

- [ ] **Step 2: Build TV APK**

Run:

```bash
./gradlew :androidTvApp:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Install on Shield**

Run:

```bash
adb -s 192.168.1.128:5555 install -r androidTvApp/build/outputs/apk/debug/androidTvApp-arm64-v8a-debug.apk
adb -s 192.168.1.128:5555 shell dumpsys package com.continuum.app.tv | rg 'versionName|lastUpdateTime'
```

Expected: install `Success`; `lastUpdateTime` matches current time.

- [ ] **Step 4: Open playback HUD and capture Info tab**

Using the Shield UI, open the same Episode 6 playback, open the HUD, and select Info.

Run:

```bash
adb -s 192.168.1.128:5555 exec-out screencap -p > /tmp/silo_hud_info_after.png
```

Expected: screenshot shows the Info tab with title, stream rows, and current chapter visible without bottom clipping.

- [ ] **Step 5: Manually inspect all tabs**

Use D-pad to visit:

```text
Info -> Stats -> Video -> Audio -> Subtitles -> Chapters
```

Expected:
- Info: no clipped row at the bottom.
- Stats: rows are readable and scroll if needed.
- Video: Quality, Speed, Aspect, HDR, Auto-skip intro, Auto-play next, Sleep timer are readable.
- Audio: track and delay rows open pickers.
- Subtitles: track, delay, appearance, search, and AI rows are reachable.
- Chapters: list scrolls, center/select seeks, and focus does not get trapped.

- [ ] **Step 6: Capture any tab that still looks wrong and fix only spacing/focus defects**

If a tab still clips, patch only `TvPlayerHud.kt` constants or pane wrappers. Example safe adjustment:

```kotlin
private val HudMaxHeight = 460.dp
private val HudPaneBottomPadding = 18.dp
```

Re-run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests 'com.continuum.app.tv.ui.screens.player.TvPlayerControlsUsabilityTest'
./gradlew :androidTvApp:assembleDebug
adb -s 192.168.1.128:5555 install -r androidTvApp/build/outputs/apk/debug/androidTvApp-arm64-v8a-debug.apk
```

Expected: tests and build pass, installed APK reflects the spacing fix.

- [ ] **Step 7: Commit Task 3**

```bash
git add androidTvApp/src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerHud.kt \
  androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerControlsUsabilityTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/theme/TvSkylineTokenParityTest.kt
git commit -m "test(tv): verify player hud tabs"
```

If Task 3 made no code changes after install/QA, skip the commit and record the verification commands in the final response.

---

## Self-Review

- Spec coverage: adaptive shell is covered by Task 1; non-clipping tab content, Info/Stats/Video/Audio/Subtitles/Chapters readability, sleep timer, focus and picker behavior are covered by Task 2 and Task 3; build/install/manual Shield QA is covered by Task 3.
- Red-flag scan: no unresolved markers are present.
- Type consistency: all new helper names referenced by tests are produced in Task 1 or Task 2 before later tasks consume them.
