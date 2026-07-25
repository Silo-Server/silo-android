# Playback-Behavior Migration Implementation Plan (Subsystem A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three playback-behavior features to silo-android — skip-back-on-resume, pass-out protection, and a remote session-control WebSocket — reusing silo's existing service-owned player.

**Architecture:** Pure logic lands in `shared`/`android-shared` (unit-testable, reusable by `androidTvApp`); UI stays in `androidApp`. The realtime socket mirrors the existing `WatchTogetherRealtimeClient` (shared, thin I/O + pure decode) bound to `PlayerViewModel` by a `PlaybackRealtimeController` that mirrors `RoomSyncController`. Server protocol already exists (`/sessions/{session_id}/control/ws`); the wire format matches `silo-server/web/src/player/realtime-protocol.ts`.

**Tech Stack:** Kotlin Multiplatform, Ktor client WebSockets, kotlinx.serialization, Koin, JUnit/kotlin.test, Compose.

**Design spec:** `docs/superpowers/specs/2026-06-17-playback-behavior-migration-design.md`

---

## Execution status (2026-06-17)

**Done + Codex-reviewed + committed (all unit tests green, both apps launch clean):**
- **F1 skip-back-on-resume — COMPLETE (mobile + TV).** Task 1 (`applyResumeRewind` b9ea8c9), Task 2 (both starters, R1 intent, 5fe12b6), B1 (TV covered by Task 2).
- **F3 remote session control — COMPLETE (mobile + TV).** Tasks 6/7/9 (models/decoder/dispatcher 01b9e05), 8 (socket client cd92cd6), 12 (DI factory 4afef1d), 10/11/13 (mobile controller 95ee49e), B6/B7 (TV controller + WT-authority gate 56fe7a9). Includes R2 (hello-on-Opened) + R3 (seek aliases).
- **F2 foundations.** Task 3 (`AutoPlayGuard` 99825ef), B3 (`nextEpisodeAfter` resolver 6187512).

**Remaining = device-test session only** (needs real playback on a visible screen; the Shield screencaps black):
- **F2 completion** — there is NO next-episode auto-advance on EITHER client yet (both are manual), so Tasks 4/5 (mobile gate + "Still watching?") and B4/B5 (TV auto-advance + gate + prompt) must BUILD auto-advance, then gate it with `AutoPlayGuard`, then verify. B2 (thread seriesId/season/episode into `Ready`) is the un-started plumbing B4 needs.
- **Live verification** — F1 actual start positions (direct vs transcode), F3 command application (pause/seek/stop/message against the service-owned player), Task 14 smoke test.

---

## ⚠ Revisions from Codex review (apply these OVER the base tasks below)

These correct real bugs Codex found in the original tasks — they affect **mobile too**, not just TV. Apply them as you execute the referenced tasks.

- **R1 — F1 resume intent (Task 2).** `isExplicitOverride = resumePositionOverride != null` is **wrong**: a genuine *resume* passes a positive override (mobile via the detail/`onPlay` path; TV via the episode-rail/home-hero/detail `onPlay` and the route's `resumePositionSeconds`), so this would skip rewind on exactly the resumes the feature targets. Conversely **Start Over (`0.0`), Watch Together anchors (`TvAppNavigation` ~L352), and `retry()` (passes current position) pass positive overrides that must NOT be rewound** — so "treat only `0.0` as explicit" is also wrong. **Fix:** thread a `suppressResumeRewind: Boolean` (or a `StartPositionIntent { Resume, StartOver, ExplicitAnchor, Retry, Default }`) on `VideoPlaybackStartRequest` and feed `applyResumeRewind(isExplicitOverride = request.suppressResumeRewind, …)`. Set `suppressResumeRewind = true` only for Start Over / WT anchor / retry; resume and bare-Play(default) get `false`. Then apply the **one** rewound value consistently to `startSession(startPosition)`, the transcode/remux fallback `seekSeconds`, lifecycle `StartParams.startPosition`, and `Ready.startPositionSeconds` (otherwise the transcode is cut from one position while the player seeks to another).
- **R2 — F3 hello race (Tasks 8, 11).** The base plan's separate `scope.launch { client.sendHello(sessionId) }` can run before `connect()` assigns `session`, so hello is a no-op and the server never marks the connection realtime-ready. **Fix:** add a `PlaybackRealtimeEvent.Opened` emitted from inside the opened-socket block, and have the controller `sendHello` when it observes `Opened` (not in a parallel launch). Applies to the mobile controller and the TV one.
- **R3 — F3 seek payload aliases (Task 9).** The web issuer sends the seek position as `position`, `position_seconds`, **or** `seconds`. `decidePlaybackAction` must accept all three (try in that order) — don't pin tests to a single spelling.
- **R4 — server-contract checks (do before/during DI + settings tasks).**
  - `PlaybackSettingsKeys.NextUpPromptSeconds` is `player.next_up_prompt_seconds` but the server registry uses `playback.next_up_prompt_seconds` — reconcile.
  - The new `player.resume_rewind_seconds` and `player.passout_threshold_episodes` keys are **not** registered server-side. Either register them server-side or keep them device-local (don't add to the server-synced `DeviceSettings` list until registered).
  - Realtime endpoint/hello/ack/result contract must be preserved; the server requires `hello` before `HasRealtimeConnection`.

TV coverage is specified in **Part B — TV coverage** at the end of this doc.

---

## File Structure

**Feature 1 — skip-back-on-resume**
- Modify: `shared/src/commonMain/kotlin/com/continuum/app/model/playback/PlaybackStartPosition.kt` (add `applyResumeRewind`)
- Modify: `shared/src/commonMain/kotlin/com/continuum/app/model/settings/PlaybackSettingsKeys.kt` (add key)
- Modify: `android-shared/.../player/video/VideoPlaybackSessionCoordinator.kt` and `androidTvApp/.../player/TvVideoPlaybackStarter.kt` (apply rewind at start)
- Test: `shared/src/commonTest/kotlin/com/continuum/app/model/playback/PlaybackStartPositionTest.kt`

**Feature 2 — pass-out protection**
- Create: `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/AutoPlayGuard.kt`
- Modify: `androidApp/.../ui/screens/player/PlayerViewModel.kt` (guard the auto-advance path; expose `stillWatching` state)
- Modify: `androidApp/.../ui/screens/player/PlayerOverlay.kt` (prompt UI)
- Modify: `shared/.../model/settings/PlaybackSettingsKeys.kt` (threshold key)
- Test: `android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/AutoPlayGuardTest.kt`

**Feature 3 — remote session-control socket**
- Create: `shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeModels.kt` (envelopes)
- Create: `shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeClient.kt` (socket + pure `decodePlaybackFrame`)
- Create: `shared/src/commonMain/kotlin/com/continuum/app/playback/PlaybackCommandDispatch.kt` (pure command→action mapper)
- Create: `androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlaybackRealtimeController.kt`
- Modify: `androidApp/.../ui/screens/player/PlayerViewModel.kt` (remote-control surface methods)
- Modify: `androidApp/.../ui/screens/player/PlayerScreen.kt` (bind controller)
- Modify: `shared/src/commonMain/kotlin/com/continuum/app/di/*` network module (register client)
- Modify: `androidApp/.../di/AndroidModule.kt` (register controller factory)
- Test: `shared/src/commonTest/kotlin/com/continuum/app/network/PlaybackRealtimeDecodeTest.kt`
- Test: `shared/src/commonTest/kotlin/com/continuum/app/playback/PlaybackCommandDispatchTest.kt`

---

# Feature 1 — Skip-back-on-resume

### Task 1: Pure resume-rewind helper  ✅ done (b9ea8c9, Codex-reviewed — +non-finite guards)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/continuum/app/model/playback/PlaybackStartPosition.kt`
- Test: `shared/src/commonTest/kotlin/com/continuum/app/model/playback/PlaybackStartPositionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/continuum/app/model/playback/PlaybackStartPositionTest.kt`:

```kotlin
package com.continuum.app.model.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStartPositionTest {
    @Test fun freshStartIsNeverRewound() {
        assertEquals(0.0, applyResumeRewind(resolvedStartPosition = 0.0, isExplicitOverride = false, rewindSeconds = 7.0))
    }

    @Test fun resumeAboveThresholdIsRewound() {
        assertEquals(593.0, applyResumeRewind(resolvedStartPosition = 600.0, isExplicitOverride = false, rewindSeconds = 7.0))
    }

    @Test fun resumeBelowThresholdIsNotRewound() {
        // 20s in is below the 30s resume-for-rewind threshold.
        assertEquals(20.0, applyResumeRewind(resolvedStartPosition = 20.0, isExplicitOverride = false, rewindSeconds = 7.0))
    }

    @Test fun rewindIsClampedAtZero() {
        assertEquals(0.0, applyResumeRewind(resolvedStartPosition = 31.0, isExplicitOverride = false, rewindSeconds = 100.0))
    }

    @Test fun explicitOverrideIsNeverRewound() {
        assertEquals(600.0, applyResumeRewind(resolvedStartPosition = 600.0, isExplicitOverride = true, rewindSeconds = 7.0))
    }

    @Test fun zeroRewindDisablesFeature() {
        assertEquals(600.0, applyResumeRewind(resolvedStartPosition = 600.0, isExplicitOverride = false, rewindSeconds = 0.0))
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew :shared:commonTest --tests "com.continuum.app.model.playback.PlaybackStartPositionTest"`
Expected: FAIL — `applyResumeRewind` unresolved.

- [ ] **Step 3: Implement the helper**

Append to `shared/src/commonMain/kotlin/com/continuum/app/model/playback/PlaybackStartPosition.kt`:

```kotlin
/** Below this resume point we do not nudge backwards (too close to the start to matter). */
const val MinResumeForRewindSeconds: Double = 30.0

/**
 * Skip-back-on-resume: when resuming a partially-watched item, begin a few
 * seconds before the saved position to re-establish context.
 *
 * Applies ONLY to a genuine resume — never to a fresh start (resolved position
 * 0) and never to an explicit override (Start Over / a commanded position).
 * Resumes below [MinResumeForRewindSeconds] are left untouched. The result is
 * clamped at 0.
 */
fun applyResumeRewind(
    resolvedStartPosition: Double,
    isExplicitOverride: Boolean,
    rewindSeconds: Double,
): Double {
    if (isExplicitOverride) return resolvedStartPosition
    if (rewindSeconds <= 0.0) return resolvedStartPosition
    if (resolvedStartPosition < MinResumeForRewindSeconds) return resolvedStartPosition
    return (resolvedStartPosition - rewindSeconds).coerceAtLeast(0.0)
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew :shared:commonTest --tests "com.continuum.app.model.playback.PlaybackStartPositionTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/continuum/app/model/playback/PlaybackStartPosition.kt \
        shared/src/commonTest/kotlin/com/continuum/app/model/playback/PlaybackStartPositionTest.kt
git commit -m "feat(playback): pure resume-rewind helper"
```

### Task 2: Setting + wire rewind into start positions

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/continuum/app/model/settings/PlaybackSettingsKeys.kt`
- Modify: `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/video/VideoPlaybackSessionCoordinator.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvVideoPlaybackStarter.kt`

- [ ] **Step 1: Add the setting key**

In `PlaybackSettingsKeys.kt`, add a constant next to the other `player.*` keys and append it to the `DeviceSettings` list:

```kotlin
const val ResumeRewindSeconds = "player.resume_rewind_seconds"
```

(Add `ResumeRewindSeconds,` to the `DeviceSettings` listOf so it round-trips to the server like the other per-device prefs.)

- [ ] **Step 2: Apply the rewind where the start position is resolved**

In `VideoPlaybackSessionCoordinator` (the mobile/shared startup path) find the existing call:

```kotlin
val startPosition = resolvePlaybackStartPosition(
    overridePosition = resumePositionOverride,
    sessionPosition = sessionResponse.positionSeconds,
    detailPosition = detailPosition,
)
```

Replace the assignment with a rewound value, reading the per-profile setting (default 7.0; `PlayerSettingsStore` is already injected here — use its existing accessor, mirroring how `sleepTimerDefaultMinutes` is read):

```kotlin
val resolvedStart = resolvePlaybackStartPosition(
    overridePosition = resumePositionOverride,
    sessionPosition = sessionResponse.positionSeconds,
    detailPosition = detailPosition,
)
val rewindSeconds = playerSettingsStore.intSetting(
    PlaybackSettingsKeys.ResumeRewindSeconds, default = 7,
).toDouble()
val startPosition = applyResumeRewind(
    resolvedStartPosition = resolvedStart,
    // See R1: a resume passes a positive override, so `override != null` is the
    // WRONG signal. Suppress rewind only for Start Over / WT anchor / retry.
    isExplicitOverride = request.suppressResumeRewind,
    rewindSeconds = rewindSeconds,
)
```

> **R1 threading (do this in Task 2):** add `val suppressResumeRewind: Boolean = false` to `VideoPlaybackStartRequest`; thread it from the route/call sites. Set it `true` for Start Over (`0.0`), Watch Together anchors, and `retry()`; leave it `false` for resume and bare Play. Then feed the **same** `startPosition` into `startSession`, the transcode/remux fallback `seekSeconds`, lifecycle `StartParams.startPosition`, and `Ready.startPositionSeconds`.

Add the imports:

```kotlin
import com.continuum.app.model.playback.applyResumeRewind
import com.continuum.app.model.settings.PlaybackSettingsKeys
```

> If `PlayerSettingsStore` has no `intSetting(key, default)` accessor, add one alongside the existing typed getters (it already backs `SleepTimerDefaultMinutes`): a suspend/flow read that parses the stored string to Int with the default fallback. Keep the name `intSetting` consistent across both call sites.

- [ ] **Step 3: Mirror in the TV starter**

In `TvVideoPlaybackStarter.kt`, locate its `resolvePlaybackStartPosition(...)` call and wrap it with the identical `applyResumeRewind(...)` block and imports from Step 2 (TV reads the same `PlaybackSettingsKeys.ResumeRewindSeconds`).

- [ ] **Step 4: Compile**

Run: `./gradlew :shared:compileKotlinMetadata :android-shared:compileDebugKotlinAndroid :androidTvApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/continuum/app/model/settings/PlaybackSettingsKeys.kt \
        android-shared/src/androidMain/kotlin/com/continuum/app/common/player/video/VideoPlaybackSessionCoordinator.kt \
        androidTvApp/src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvVideoPlaybackStarter.kt
git commit -m "feat(playback): apply resume-rewind to mobile and TV start positions"
```

---

# Feature 2 — Pass-out protection

### Task 3: AutoPlayGuard (pure)

**Files:**
- Create: `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/AutoPlayGuard.kt`
- Test: `android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/AutoPlayGuardTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.continuum.app.common.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPlayGuardTest {
    @Test fun gatesExactlyAtThreshold() {
        val guard = AutoPlayGuard(threshold = 3)
        guard.recordAutoAdvance() // 1
        guard.recordAutoAdvance() // 2
        assertFalse(guard.shouldGate())
        guard.recordAutoAdvance() // 3
        assertTrue(guard.shouldGate())
    }

    @Test fun userActionResetsCounter() {
        val guard = AutoPlayGuard(threshold = 3)
        repeat(3) { guard.recordAutoAdvance() }
        assertTrue(guard.shouldGate())
        guard.recordUserAction()
        assertFalse(guard.shouldGate())
    }

    @Test fun continueAfterPromptResetsAndAllowsMore() {
        val guard = AutoPlayGuard(threshold = 3)
        repeat(3) { guard.recordAutoAdvance() }
        guard.recordUserAction() // user tapped "Continue"
        guard.recordAutoAdvance() // 1
        assertFalse(guard.shouldGate())
    }

    @Test fun thresholdZeroNeverGates() {
        val guard = AutoPlayGuard(threshold = 0)
        repeat(10) { guard.recordAutoAdvance() }
        assertFalse(guard.shouldGate())
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew :android-shared:testDebugUnitTest --tests "com.continuum.app.common.player.AutoPlayGuardTest"`
Expected: FAIL — `AutoPlayGuard` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.continuum.app.common.player

/**
 * Pass-out protection. Counts consecutive AUTO-advanced episodes and reports
 * when the next auto-advance should be gated behind a "Still watching?" prompt.
 * A threshold of 0 disables gating. Any user-initiated action resets the count.
 *
 * Pure and single-threaded by contract — call from the player's main scope.
 */
class AutoPlayGuard(private val threshold: Int) {
    private var consecutiveAutoAdvances: Int = 0

    /** Record that an episode was auto-advanced (not user-chosen). */
    fun recordAutoAdvance() {
        consecutiveAutoAdvances += 1
    }

    /** Any deliberate user action (manual play/seek/next, or tapping "Continue"). */
    fun recordUserAction() {
        consecutiveAutoAdvances = 0
    }

    /** True when the next auto-advance should be blocked by the prompt. */
    fun shouldGate(): Boolean = threshold > 0 && consecutiveAutoAdvances >= threshold
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `./gradlew :android-shared:testDebugUnitTest --tests "com.continuum.app.common.player.AutoPlayGuardTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add android-shared/src/androidMain/kotlin/com/continuum/app/common/player/AutoPlayGuard.kt \
        android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/AutoPlayGuardTest.kt
git commit -m "feat(playback): AutoPlayGuard for pass-out protection"
```

### Task 4: Gate the auto-advance path in PlayerViewModel

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/continuum/app/model/settings/PlaybackSettingsKeys.kt`

- [ ] **Step 1: Add the threshold key**

In `PlaybackSettingsKeys.kt` add and include in `DeviceSettings`:

```kotlin
const val PassoutThresholdEpisodes = "player.passout_threshold_episodes"
```

- [ ] **Step 2: Add guard + state to the ViewModel**

In `PlayerViewModel`, add a field built from the setting (default 3) and a UI flag. Near the other private state:

```kotlin
private val autoPlayGuard = AutoPlayGuard(
    threshold = playerSettingsStore.intSetting(PlaybackSettingsKeys.PassoutThresholdEpisodes, default = 3),
)
```

Add to `UiState` (the `data class` around line 120) a new field:

```kotlin
val stillWatchingPrompt: Boolean = false,
```

Add imports:

```kotlin
import com.continuum.app.common.player.AutoPlayGuard
import com.continuum.app.model.settings.PlaybackSettingsKeys
```

- [ ] **Step 3: Split manual vs auto advance**

The existing `onNextEpisode()` (~line 922) is the MANUAL path — at its top, reset the guard:

```kotlin
fun onNextEpisode() {
    autoPlayGuard.recordUserAction()
    advanceToNextEpisode()
}
```

Add a private `advanceToNextEpisode()` containing the body that previously lived in `onNextEpisode()` (the actual load-next logic). Then add the AUTO entry point used by the credits/auto-play trigger:

```kotlin
/** Auto-advance entry point. Gates behind the "Still watching?" prompt at threshold. */
private fun autoAdvanceToNextEpisode() {
    if (autoPlayGuard.shouldGate()) {
        _uiState.update { it.copy(stillWatchingPrompt = true) }
        return
    }
    autoPlayGuard.recordAutoAdvance()
    advanceToNextEpisode()
}
```

Find the existing automatic next-episode trigger (where `autoPlayNextEnabled` is honored as credits are reached — the code that currently calls the next-episode load automatically) and route it through `autoAdvanceToNextEpisode()` instead of the manual path.

- [ ] **Step 4: Prompt responses + reset on user transport**

Add handlers:

```kotlin
fun onStillWatchingContinue() {
    autoPlayGuard.recordUserAction()
    _uiState.update { it.copy(stillWatchingPrompt = false) }
    advanceToNextEpisode()
}

fun onStillWatchingStop() {
    _uiState.update { it.copy(stillWatchingPrompt = false) }
    // Leave the player on the prompt's parent; do not advance.
}
```

In the existing manual transport handlers — `onSeek(...)` and the play/pause toggle — add `autoPlayGuard.recordUserAction()` at the top so deliberate interaction resets the streak.

- [ ] **Step 5: Compile**

Run: `./gradlew :androidApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerViewModel.kt \
        shared/src/commonMain/kotlin/com/continuum/app/model/settings/PlaybackSettingsKeys.kt
git commit -m "feat(playback): gate auto-advance with pass-out protection"
```

### Task 5: "Still watching?" prompt UI

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerOverlay.kt`

- [ ] **Step 1: Add the prompt composable**

Add to `PlayerOverlay.kt`:

```kotlin
@Composable
private fun StillWatchingPrompt(
    onContinue: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Still watching?", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onStop) { Text("Stop") }
                    Button(onClick = onContinue) { Text("Continue") }
                }
            }
        }
    }
}
```

Ensure these imports exist (add any missing): `androidx.compose.foundation.background`, `androidx.compose.foundation.layout.*`, `androidx.compose.material3.{Button, TextButton, Surface, Text, MaterialTheme}`, `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.unit.dp`, `androidx.compose.ui.Alignment`.

- [ ] **Step 2: Render it from overlay state**

In the overlay's root content, after the existing overlays, add:

```kotlin
if (state.stillWatchingPrompt) {
    StillWatchingPrompt(
        onContinue = viewModel::onStillWatchingContinue,
        onStop = viewModel::onStillWatchingStop,
    )
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :androidApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerOverlay.kt
git commit -m "feat(playback): Still watching? prompt UI"
```

---

# Feature 3 — Remote session-control socket

### Task 6: Realtime envelope models

**Files:**
- Create: `shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeModels.kt`

- [ ] **Step 1: Define the wire models**

These match `silo-server/internal/playback/realtime.go` and `web/src/player/realtime-protocol.ts` exactly.

```kotlin
package com.continuum.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Command names the server may send (mirror realtime-protocol.ts ALL_PLAYBACK_COMMANDS). */
object PlaybackCommandNames {
    const val Pause = "pause"
    const val Unpause = "unpause"
    const val PlayPause = "play_pause"
    const val Seek = "seek"
    const val SetVolume = "set_volume"
    const val Stop = "stop"
    const val Terminate = "terminate"
    const val DisplayMessage = "display_message"
    const val ServerRestarting = "server_restarting"
    const val ServerShuttingDown = "server_shutting_down"
    const val PlayMedia = "play_media"
    const val SetAudioTrack = "set_audio_track"
    const val SetSubtitleTrack = "set_subtitle_track"

    /** What this client advertises in hello + actually handles (mirror SUPPORTED_PLAYBACK_COMMANDS). */
    val Supported = listOf(
        Pause, Unpause, PlayPause, Seek, SetVolume, Stop, Terminate,
        DisplayMessage, ServerRestarting, ServerShuttingDown,
    )
}

@Serializable
data class PlaybackHelloEnvelope(
    val type: String = "hello",
    @SerialName("session_id") val sessionId: String,
    val client: HelloClient,
    val capabilities: HelloCapabilities,
)

@Serializable
data class HelloClient(val name: String = "silo-android", val version: String = "1")

@Serializable
data class HelloCapabilities(val commands: List<String>)

@Serializable
data class PlaybackAckEnvelope(
    val type: String = "ack",
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String,
    val status: String = "accepted",
)

@Serializable
data class PlaybackResultEnvelope(
    val type: String = "result",
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String,
    val status: String, // "completed" | "rejected"
    val error: String? = null,
)

/** Parsed inbound message surfaced to the controller. */
sealed interface PlaybackRealtimeEvent {
    /** Emitted once the socket is open (R2) — the controller sends hello on this. */
    data object Opened : PlaybackRealtimeEvent

    data class Command(
        val commandId: String,
        val sessionId: String,
        val name: String,
        val payload: JsonObject,
    ) : PlaybackRealtimeEvent

    data class ServerEvent(
        val sessionId: String,
        val name: String,
        val payload: JsonObject,
    ) : PlaybackRealtimeEvent

    data class Closed(val reason: String? = null) : PlaybackRealtimeEvent
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeModels.kt
git commit -m "feat(realtime): playback control envelope models"
```

### Task 7: Pure frame decoder

**Files:**
- Create: `shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeClient.kt` (decoder portion first)
- Test: `shared/src/commonTest/kotlin/com/continuum/app/network/PlaybackRealtimeDecodeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.continuum.app.network

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRealtimeDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decodesSeekCommand() {
        val raw = """{"type":"command","command_id":"c1","session_id":"s1","name":"seek","payload":{"position_seconds":42.5}}"""
        val ev = decodePlaybackFrame(json, raw)
        assertTrue(ev is PlaybackRealtimeEvent.Command)
        ev as PlaybackRealtimeEvent.Command
        assertEquals("seek", ev.name)
        assertEquals("c1", ev.commandId)
    }

    @Test fun decodesMarkersEvent() {
        val raw = """{"type":"event","session_id":"s1","name":"markers_updated","payload":{"file_id":7}}"""
        val ev = decodePlaybackFrame(json, raw)
        assertTrue(ev is PlaybackRealtimeEvent.ServerEvent)
    }

    @Test fun unknownTypeReturnsNull() {
        assertNull(decodePlaybackFrame(json, """{"type":"pong"}"""))
    }

    @Test fun malformedJsonReturnsNull() {
        assertNull(decodePlaybackFrame(json, "not json"))
    }

    @Test fun commandMissingFieldsReturnsNull() {
        assertNull(decodePlaybackFrame(json, """{"type":"command","name":"seek"}"""))
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew :shared:commonTest --tests "com.continuum.app.network.PlaybackRealtimeDecodeTest"`
Expected: FAIL — `decodePlaybackFrame` unresolved.

- [ ] **Step 3: Implement the decoder**

Create `shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeClient.kt` with (for now) just the pure decoder:

```kotlin
package com.continuum.app.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure decode of one control-socket server frame into a [PlaybackRealtimeEvent],
 * or null when the frame is not one we handle (unknown type, missing fields,
 * malformed JSON). Never throws. This is the load-bearing tested logic; the
 * socket I/O in [DefaultPlaybackRealtimeClient] is kept thin.
 */
fun decodePlaybackFrame(json: Json, raw: String): PlaybackRealtimeEvent? {
    val obj: JsonObject = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        return null
    }
    fun str(key: String) = (obj[key] as? JsonPrimitive)?.content
    val type = str("type") ?: return null
    val sessionId = str("session_id") ?: return null
    val payload = (obj["payload"] as? JsonObject) ?: JsonObject(emptyMap())
    return when (type) {
        "command" -> {
            val commandId = str("command_id") ?: return null
            val name = str("name") ?: return null
            PlaybackRealtimeEvent.Command(commandId, sessionId, name, payload)
        }
        "event" -> {
            val name = str("name") ?: return null
            PlaybackRealtimeEvent.ServerEvent(sessionId, name, payload)
        }
        else -> null
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `./gradlew :shared:commonTest --tests "com.continuum.app.network.PlaybackRealtimeDecodeTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeClient.kt \
        shared/src/commonTest/kotlin/com/continuum/app/network/PlaybackRealtimeDecodeTest.kt
git commit -m "feat(realtime): pure playback control frame decoder"
```

### Task 8: Socket client (thin I/O)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeClient.kt`

This mirrors `DefaultWatchTogetherRealtimeClient`. There is no testable logic added here (I/O only), so no new unit test — coverage stays on the decoder and the dispatcher.

- [ ] **Step 1: Add the interface + implementation**

Add these imports to the **top** of `PlaybackRealtimeClient.kt` (with the existing imports — Kotlin imports must precede declarations), then add the interface and class **below** the `decodePlaybackFrame` function:

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Per-session control socket. One [connect] = one connection to
 * `/api/v1/playback/sessions/{session_id}/control/ws`, authenticated by query
 * string (token + profile, matching [DefaultWatchTogetherRealtimeClient]).
 * The returned flow ends with [PlaybackRealtimeEvent.Closed]; reconnect with
 * backoff is the controller's job. [sendHello]/[sendAck]/[sendResult] write on
 * the open session.
 */
interface PlaybackRealtimeClient {
    fun connect(sessionId: String): Flow<PlaybackRealtimeEvent>
    suspend fun sendHello(sessionId: String)
    suspend fun sendAck(sessionId: String, commandId: String)
    suspend fun sendResult(sessionId: String, commandId: String, status: String, error: String? = null)
}

class DefaultPlaybackRealtimeClient(
    private val client: HttpClient,
    private val tokenManager: TokenManager,
    private val json: kotlinx.serialization.json.Json = ContinuumJson,
) : PlaybackRealtimeClient {

    private var session: DefaultClientWebSocketSession? = null

    override fun connect(sessionId: String): Flow<PlaybackRealtimeEvent> = callbackFlow {
        val token = tokenManager.getAccessToken()
        val profileId = tokenManager.getProfileId()
        val profileToken = tokenManager.getProfileToken()
        if (token.isNullOrBlank() || profileId.isNullOrBlank()) {
            trySend(PlaybackRealtimeEvent.Closed("missing_auth"))
            close()
            return@callbackFlow
        }
        val url = buildString {
            append("/api/v1/playback/sessions/")
            append(sessionId.encodeURLParameter())
            append("/control/ws?token=").append(token.encodeURLParameter())
            append("&profile_id=").append(profileId.encodeURLParameter())
            if (!profileToken.isNullOrBlank()) {
                append("&profile_token=").append(profileToken.encodeURLParameter())
            }
        }
        try {
            client.webSocket(urlString = url) {
                session = this
                // R2: signal open AFTER the session is assigned, so the
                // controller's hello can't race ahead of a live socket.
                trySend(PlaybackRealtimeEvent.Opened)
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        decodePlaybackFrame(json, frame.readText())?.let { trySend(it) }
                    }
                } finally {
                    session = null
                }
            }
            trySend(PlaybackRealtimeEvent.Closed())
        } catch (e: Throwable) {
            session = null
            trySend(PlaybackRealtimeEvent.Closed(e.message))
        } finally {
            close()
        }
        awaitClose { }
    }

    private suspend fun sendText(text: String) { session?.send(Frame.Text(text)) }

    override suspend fun sendHello(sessionId: String) = sendText(
        json.encodeToString(
            PlaybackHelloEnvelope.serializer(),
            PlaybackHelloEnvelope(
                sessionId = sessionId,
                client = HelloClient(),
                capabilities = HelloCapabilities(PlaybackCommandNames.Supported),
            ),
        ),
    )

    override suspend fun sendAck(sessionId: String, commandId: String) = sendText(
        json.encodeToString(
            PlaybackAckEnvelope.serializer(),
            PlaybackAckEnvelope(commandId = commandId, sessionId = sessionId),
        ),
    )

    override suspend fun sendResult(sessionId: String, commandId: String, status: String, error: String?) = sendText(
        json.encodeToString(
            PlaybackResultEnvelope.serializer(),
            PlaybackResultEnvelope(commandId = commandId, sessionId = sessionId, status = status, error = error),
        ),
    )
}
```

> `ContinuumJson` and `TokenManager` are the same symbols `DefaultWatchTogetherRealtimeClient` imports — copy those import lines from `WatchTogetherRealtimeClient.kt` if the IDE does not auto-resolve them.

- [ ] **Step 2: Compile**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/continuum/app/network/PlaybackRealtimeClient.kt
git commit -m "feat(realtime): playback control socket client"
```

### Task 9: Pure command→action dispatcher

**Files:**
- Create: `shared/src/commonMain/kotlin/com/continuum/app/playback/PlaybackCommandDispatch.kt`
- Test: `shared/src/commonTest/kotlin/com/continuum/app/playback/PlaybackCommandDispatchTest.kt`

This is the testable core of the controller: given a command, decide the action + result status, without touching the player.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.continuum.app.playback

import com.continuum.app.network.PlaybackRealtimeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackCommandDispatchTest {
    private val json = Json
    private fun cmd(name: String, payload: String = "{}") =
        PlaybackRealtimeEvent.Command("c1", "s1", name, json.parseToJsonElement(payload).jsonObject)

    @Test fun pauseMapsToPause() {
        assertEquals(PlaybackAction.Pause, decidePlaybackAction(cmd("pause")))
    }

    @Test fun seekReadsPositionSeconds() {
        assertEquals(PlaybackAction.SeekTo(42.5), decidePlaybackAction(cmd("seek", """{"position_seconds":42.5}""")))
    }

    @Test fun seekReadsPositionAlias() {
        assertEquals(PlaybackAction.SeekTo(10.0), decidePlaybackAction(cmd("seek", """{"position":10.0}""")))
    }

    @Test fun seekReadsSecondsAlias() {
        assertEquals(PlaybackAction.SeekTo(5.0), decidePlaybackAction(cmd("seek", """{"seconds":5.0}""")))
    }

    @Test fun displayMessageReadsMessage() {
        assertEquals(PlaybackAction.ShowMessage("hi"), decidePlaybackAction(cmd("display_message", """{"message":"hi"}""")))
    }

    @Test fun stopMapsToStop() {
        assertEquals(PlaybackAction.Stop, decidePlaybackAction(cmd("terminate")))
    }

    @Test fun unsupportedCommandIsRejected() {
        assertEquals(PlaybackAction.Reject, decidePlaybackAction(cmd("play_media")))
    }

    @Test fun malformedSeekIsRejected() {
        assertEquals(PlaybackAction.Reject, decidePlaybackAction(cmd("seek", """{}""")))
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew :shared:commonTest --tests "com.continuum.app.playback.PlaybackCommandDispatchTest"`
Expected: FAIL — `decidePlaybackAction`/`PlaybackAction` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.continuum.app.playback

import com.continuum.app.network.PlaybackCommandNames
import com.continuum.app.network.PlaybackRealtimeEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A resolved player action for a remote command. [Reject] → result status "rejected". */
sealed interface PlaybackAction {
    data object Pause : PlaybackAction
    data object Unpause : PlaybackAction
    data object TogglePlayPause : PlaybackAction
    data class SeekTo(val positionSeconds: Double) : PlaybackAction
    data class ShowMessage(val message: String) : PlaybackAction
    data object Stop : PlaybackAction
    data object Ignore : PlaybackAction
    data object Reject : PlaybackAction
}

/**
 * Pure mapping from a realtime command to a player action. Unsupported or
 * malformed commands map to [PlaybackAction.Reject]; informational commands we
 * accept but do nothing about map to [PlaybackAction.Ignore].
 */
fun decidePlaybackAction(command: PlaybackRealtimeEvent.Command): PlaybackAction {
    fun double(key: String): Double? =
        (command.payload[key] as? JsonPrimitive)?.doubleOrNull
    fun string(key: String): String? =
        (command.payload[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    return when (command.name) {
        PlaybackCommandNames.Pause -> PlaybackAction.Pause
        PlaybackCommandNames.Unpause -> PlaybackAction.Unpause
        PlaybackCommandNames.PlayPause -> PlaybackAction.TogglePlayPause
        PlaybackCommandNames.Seek ->
            // R3: the issuer may send any of these spellings.
            (double("position_seconds") ?: double("position") ?: double("seconds"))
                ?.let { PlaybackAction.SeekTo(it) } ?: PlaybackAction.Reject
        PlaybackCommandNames.DisplayMessage ->
            string("message")?.let { PlaybackAction.ShowMessage(it) } ?: PlaybackAction.Reject
        PlaybackCommandNames.Stop, PlaybackCommandNames.Terminate -> PlaybackAction.Stop
        // Accepted but no client-side action needed.
        PlaybackCommandNames.SetVolume,
        PlaybackCommandNames.ServerRestarting,
        PlaybackCommandNames.ServerShuttingDown -> PlaybackAction.Ignore
        else -> PlaybackAction.Reject
    }
}
```

> `JsonPrimitive.isString` distinguishes a JSON string from a bare number; `doubleOrNull` parses numeric primitives. Confirm `position_seconds` is the field the issuer sends (silo uses `position_seconds` consistently in watch-together); the decode test pins it.

- [ ] **Step 4: Run, verify it passes**

Run: `./gradlew :shared:commonTest --tests "com.continuum.app.playback.PlaybackCommandDispatchTest"`
Expected: PASS (8 tests — includes the two seek-alias cases from R3).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/continuum/app/playback/PlaybackCommandDispatch.kt \
        shared/src/commonTest/kotlin/com/continuum/app/playback/PlaybackCommandDispatchTest.kt
git commit -m "feat(realtime): pure command-to-action dispatcher"
```

### Task 10: Remote-control surface on PlayerViewModel

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerViewModel.kt`

Add a small, explicit surface the controller calls — wrapping methods the VM already has (`onSeek`/`seekImmediate`, play/pause toggle, `stop`, `refreshSubtitles`, the notice-overlay message channel). Defining these here keeps the controller decoupled from VM internals.

- [ ] **Step 1: Add remote-control methods**

```kotlin
// ---- Remote session-control surface (driven by PlaybackRealtimeController) ----

fun remotePause() { setPlayWhenReady(false) }           // existing internal play/pause setter
fun remoteUnpause() { setPlayWhenReady(true) }
fun remoteTogglePlayPause() { togglePlayPause() }        // existing
fun remoteSeek(positionSeconds: Double) { seekImmediate(positionSeconds) } // existing immediate-seek
fun remoteStop() { stopPlayback() }                      // existing teardown used on exit
fun remoteDisplayMessage(message: String) { showNotice(message) } // existing PlayerNoticeOverlay channel
```

> Replace each right-hand call with the VM's actual existing method if the name differs (e.g. the play/pause setter, the immediate-seek used by chapter seek at ~line 639, the stop/teardown used when leaving the screen, and the notice emitter feeding `PlayerNoticeOverlay`). Do not add new playback logic — these are thin adapters.

- [ ] **Step 2: Compile**

Run: `./gradlew :androidApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerViewModel.kt
git commit -m "feat(realtime): remote-control surface on PlayerViewModel"
```

### Task 11: PlaybackRealtimeController

**Files:**
- Create: `androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlaybackRealtimeController.kt`

Mirrors `RoomSyncController`: built once per `sessionId`, owns the connect/reconnect loop, applies actions, sends ack/result, and consumes server events by calling the VM's existing refreshers.

- [ ] **Step 1: Implement the controller**

```kotlin
package com.continuum.app.android.ui.screens.player

import com.continuum.app.network.PlaybackRealtimeClient
import com.continuum.app.network.PlaybackRealtimeEvent
import com.continuum.app.playback.PlaybackAction
import com.continuum.app.playback.decidePlaybackAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Binds the per-session playback control socket to [PlayerViewModel] for the
 * lifetime of a player screen. Active whenever a playback [sessionId] exists.
 * Applies remote commands via the VM remote-control surface and acks/results
 * each one; refreshes tracks/markers on server events. Socket loss does not
 * interrupt playback — the loop reconnects with capped backoff.
 */
class PlaybackRealtimeController(
    private val sessionId: String,
    private val client: PlaybackRealtimeClient,
    private val viewModel: PlayerViewModel,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val BACKOFF_START_MS = 2_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val STATUS_COMPLETED = "completed"
        const val STATUS_REJECTED = "rejected"
    }

    fun start() {
        scope.launch {
            var backoff = BACKOFF_START_MS
            while (isActive) {
                client.connect(sessionId).collectLatest { event ->
                    when (event) {
                        // R2: hello only AFTER the socket is open, so the server
                        // marks the connection control-ready (it requires hello
                        // before HasRealtimeConnection). No parallel launch.
                        is PlaybackRealtimeEvent.Opened -> client.sendHello(sessionId)
                        is PlaybackRealtimeEvent.Closed -> { /* fall through to reconnect */ }
                        is PlaybackRealtimeEvent.Command -> handleCommand(event)
                        is PlaybackRealtimeEvent.ServerEvent -> handleServerEvent(event)
                    }
                }
                // connect() flow completed → socket closed; back off and retry.
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    private suspend fun handleCommand(cmd: PlaybackRealtimeEvent.Command) {
        client.sendAck(sessionId, cmd.commandId)
        val action = decidePlaybackAction(cmd)
        val status = when (action) {
            is PlaybackAction.Pause -> { viewModel.remotePause(); STATUS_COMPLETED }
            is PlaybackAction.Unpause -> { viewModel.remoteUnpause(); STATUS_COMPLETED }
            is PlaybackAction.TogglePlayPause -> { viewModel.remoteTogglePlayPause(); STATUS_COMPLETED }
            is PlaybackAction.SeekTo -> { viewModel.remoteSeek(action.positionSeconds); STATUS_COMPLETED }
            is PlaybackAction.ShowMessage -> { viewModel.remoteDisplayMessage(action.message); STATUS_COMPLETED }
            is PlaybackAction.Stop -> { viewModel.remoteStop(); STATUS_COMPLETED }
            is PlaybackAction.Ignore -> STATUS_COMPLETED
            is PlaybackAction.Reject -> STATUS_REJECTED
        }
        client.sendResult(sessionId, cmd.commandId, status)
    }

    private fun handleServerEvent(event: PlaybackRealtimeEvent.ServerEvent) {
        when (event.name) {
            "subtitle_ready" -> viewModel.refreshSubtitles()
            "markers_updated" -> viewModel.refreshMarkers()
            // chapter_thumbnail_ready / subtitle_translation_* handled by existing flows later.
            else -> { /* ignore */ }
        }
    }
}
```

> `viewModel.refreshSubtitles()` already exists (web-parity refresh ~line 777). If `refreshMarkers()` does not exist, add a thin VM method that re-fetches intro/credits markers for the current file (the data the VM already loads at startup); otherwise omit the `markers_updated` branch for this iteration and note it.

- [ ] **Step 2: Compile**

Run: `./gradlew :androidApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlaybackRealtimeController.kt
git commit -m "feat(realtime): PlaybackRealtimeController binds socket to player"
```

### Task 12: DI registration

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/continuum/app/di/RepositoryModule.kt` (or the network module where `DefaultWatchTogetherRealtimeClient` is bound)
- Modify: `androidApp/src/androidMain/kotlin/com/continuum/app/android/di/AndroidModule.kt`

- [ ] **Step 1: Bind the client in shared DI**

Find where `WatchTogetherRealtimeClient` is bound (the same module) and add the parallel binding:

```kotlin
single<PlaybackRealtimeClient> { DefaultPlaybackRealtimeClient(client = get(), tokenManager = get()) }
```

Add `import com.continuum.app.network.PlaybackRealtimeClient` / `DefaultPlaybackRealtimeClient`.

- [ ] **Step 2: Compile**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/continuum/app/di/RepositoryModule.kt
git commit -m "feat(realtime): bind PlaybackRealtimeClient in DI"
```

### Task 13: Bind controller in PlayerScreen

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerScreen.kt`

Mirror the `RoomSyncController` binding (built once per key, here `sessionId`).

- [ ] **Step 1: Build + start the controller when a sessionId exists**

Near the existing `RoomSyncController` binding (~line 123), add:

```kotlin
val playbackRealtimeClient: PlaybackRealtimeClient = koinInject()
val sessionId = uiState.sessionId
LaunchedEffect(sessionId) {
    val id = sessionId ?: return@LaunchedEffect
    PlaybackRealtimeController(
        sessionId = id,
        client = playbackRealtimeClient,
        viewModel = viewModel,
        scope = this, // LaunchedEffect coroutine scope; cancelled when sessionId changes/leaves
    ).start()
}
```

Add imports: `org.koin.compose.koinInject`, `androidx.compose.runtime.LaunchedEffect`, `com.continuum.app.network.PlaybackRealtimeClient`. `uiState` is the already-collected player state; use the same accessor the screen already uses for `uiState.sessionId`.

- [ ] **Step 2: Compile**

Run: `./gradlew :androidApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerScreen.kt
git commit -m "feat(realtime): attach PlaybackRealtimeController in PlayerScreen"
```

### Task 14: Full-suite verification

- [ ] **Step 1: Run all affected unit tests**

Run:
```bash
./gradlew :shared:commonTest :android-shared:testDebugUnitTest
```
Expected: PASS, including `PlaybackStartPositionTest`, `AutoPlayGuardTest`, `PlaybackRealtimeDecodeTest`, `PlaybackCommandDispatchTest`.

- [ ] **Step 2: Compile all Android targets**

Run:
```bash
./gradlew :androidApp:compileDebugKotlinAndroid :androidTvApp:compileDebugKotlinAndroid
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test (device/emulator)**

1. Resume a movie watched past 30s → playback starts ~7s before the saved position; start a fresh movie → no rewind.
2. Auto-play 3 episodes in a row → "Still watching?" appears on the 3rd auto-advance; "Continue" resumes; a manual next never triggers it.
3. With a session playing, issue an admin pause/seek/stop/message from the web admin session-control UI → the Android player responds.

- [ ] **Step 4: Final commit (if smoke test required tweaks)**

```bash
git add -A && git commit -m "test(playback): subsystem A verification fixes"
```

---

## Notes for the implementer

- **No lift-and-shift from continuum.** Continuum used Decompose + an app-level ExoPlayer; silo uses Compose Navigation + a service-owned player. Mirror silo's own patterns (`WatchTogetherRealtimeClient`, `RoomSyncController`, `PlayerViewModel`).
- **Reuse, don't duplicate.** Every remote-control method and event handler calls a method the VM already exposes.
- **Out of scope (this plan):** `play_media`, `set_audio_track`, `set_subtitle_track`, `set_volume` actioning — these map to `Ignore`/`Reject` for now (the client does not advertise the track/media commands in `Supported`).
- **TV adoption** of `AutoPlayGuard` and the rewound start position is specified in **Part B** below (the product owner asked Subsystem A to cover TV too).

---

# Part B — TV coverage

Extends all three features to `androidTvApp`. F1 is essentially free once R1 lands; F3-TV mirrors the phone wiring over the same shared core; F2-TV is bundled with **P6.4** (TV next-episode auto-advance, which does not exist yet) and is **device-test-gated** along with F3 command application.

**Execution order:** B1 → B2 → B3 (all buildable + unit-testable) → B6 → B7 (buildable, fake-client tests) → B4 → B5 (device-test). Codex-review + commit per task, same as Part A.

### Task B1 — F1 on TV (verify, no new code beyond R1)
Task 2 Step 3 already wires `applyResumeRewind` into `TvVideoPlaybackStarter`. With **R1** in place (`suppressResumeRewind` on `VideoPlaybackStartRequest`), set the flag correctly at the TV call sites:
- `TvVideoPlaybackStarter` resume / bare-Play → `false` (rewind applies).
- Start Over (`TvItemDetailScreen` `0.0`), Watch Together anchor (`TvAppNavigation` ~L352), and `TvPlayerViewModel.retry()` (passes current position ~L1299) → `true` (no rewind).
- [ ] Thread `suppressResumeRewind` from `TvRoute.Player` (new optional arg, default false) → `TvPlayerLaunchArgs` → `VideoPlaybackStartRequest`; Start Over sets it true. WT/retry set it true at their construction sites.
- [ ] Device-test: resume an episode → starts ~7s back; Start Over / retry / WT join → no rewind.

### Task B2 — Thread episode metadata into the Ready state (shared + TV)
- [ ] Add `seriesId`, `seasonNumber`, `episodeNumber`, `seriesTitle` to `VideoPlaybackStartResult.Ready` and `VideoPlayerUiState.Ready` (`android-shared/.../video/`), populated from `WatchDetail` in the coordinator/starter. (Phone ignores them; TV consumes them.)
- [ ] Add the same fields to `TvPlayerViewModel.UiState` and copy them through in `loadContent`'s Ready branch.
- [ ] Compile `:android-shared :androidApp :androidTvApp`.

### Task B3 — Pure next-episode resolver (shared, TDD)
- Create `shared/.../playback/NextEpisodeResolver.kt` + test.
- [ ] Pure function: given current (seriesId, season, episode) + the season/episode lists, return the next playable episode (next in current season, else first of next season, else null at series end; handle specials/season 0 ordering).
- [ ] Tests: mid-season, season boundary, end of series, specials ordering. (No network — feed it lists.)

### Task B4 — TV next-episode auto-advance + pass-out gate (P6.4) — **device-test**
- [ ] Inject `CatalogRepository` into `TvPlayerViewModel`; after Ready, resolve next-up via `getSeasons()` + `getEpisodes()` (current + next season) using Task B3's pure resolver; store `NextEpisodeState(contentId, season, episode, title, stillUrl, stillThumbhash)`.
- [ ] Build `AutoPlayGuard` (Task 3) into `TvPlayerViewModel` from `PlaybackSettingsKeys.PassoutThresholdEpisodes`.
- [ ] Auto-advance trigger: primarily when position reaches `credits.start` (only when `autoPlayNextEnabled` and a next episode exists); add `viewModel.onPlaybackEnded()` driven by a new `Player.STATE_ENDED` branch in `TvPlayerScreen` (currently only buffering is handled ~L476) as fallback.
- [ ] Route auto-advance through a gated `autoAdvanceToNextEpisode()` (mirror Task 4): if `guard.shouldGate()` → set `stillWatchingPrompt`; else `recordAutoAdvance()` + load next (re-mount player on the next contentId via the existing nav, carrying resume=null/start). Manual transport (`onPlayPause`, `seekImmediate`) and a manual "next" call `recordUserAction()`.
- [ ] Device-test: 3 auto-advances → prompt; manual next never prompts.

### Task B5 — TV "Still watching?" overlay — **device-test**
- [ ] Add `stillWatchingPrompt: Boolean` to `TvPlayerViewModel.UiState` + `onStillWatchingContinue()` (recordUserAction + advance) / `onStillWatchingStop()` (clear, don't advance).
- [ ] Render a focusable full-screen dim modal in `TvPlayerScreen` mirroring `TvRoomCloseConfirmDialog` (~L1296) with Continue / Stop via `TvDialogActionRow`; auto-focus Continue; Back = Stop.
- [ ] Device-test: focus, Continue resumes, Stop stays, Back behaves.

### Task B6 — TV remote-control surface on TvPlayerViewModel
Mirror Task 10. Map to **existing** TV methods (Codex-confirmed):
- `remotePause()` → `setPaused(true)` (~L746); `remoteUnpause()` → `setPaused(false)`.
- `remoteTogglePlayPause()` → `onPlayPause()` (~L735).
- `remoteSeek(s)` → `seekImmediate(s)` (~L759; screen collects `seekRequests`).
- `remoteStop()` → **needs a bridge**: `stopPlaybackAndExit` is screen-local (`TvPlayerScreen` ~L227). Add a VM exit-request flow the screen collects (or pass an `onStop` callback into the controller from the screen).
- `remoteDisplayMessage(msg)` → **new**: add a remote-notice state/event on the VM (the existing `notice` is lifecycle-owned/read-only ~L414) and render it via `TvPlayerNoticeOverlay` (~L986).
- [ ] Compile `:androidTvApp`.

### Task B7 — TvPlaybackRealtimeController + bind in TvPlayerScreen
- Create `androidTvApp/.../screens/player/TvPlaybackRealtimeController.kt` mirroring Task 11 (reuses the shared `PlaybackRealtimeClient` / `decidePlaybackAction`; **R2** hello-on-`Opened`; the `onStop` bridge from B6).
- [ ] Bind in `TvPlayerScreen` keyed on `uiState.sessionId` (mirror the `RoomSyncController`/Player binding), `koinInject()` the client.
- [ ] Optional: a fake-client controller test (pure-ish) asserting ack→action→result for pause/seek/stop/message.
- [ ] Device-test: admin pause/seek/stop/message against a live TV session.
