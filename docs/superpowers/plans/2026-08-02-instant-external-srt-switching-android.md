# Instant External SRT Switching Android Implementation Plan

> Superseded protocol note (2026-08-06): the platform-neutral v3 contract no
> longer defines `external_text_sidecar_set_v1`. Subtitle support is advertised
> per delivery through `subtitles.sidecar_text`, and the server publishes the
> authoritative `playback_plan.subtitle.inventory`. The feature-token steps
> below are retained only as pre-neutral implementation history.
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mount a negotiated server-provided external SRT/VTT set in Media3 while preserving the current selected-artifact and staged-replan behavior against older servers.

**Architecture:** Extend the tolerant Kotlin V3 model, advertise the feature only through the local Media3 playback context, and merge valid sidecars into the existing `PlayerSubtitleInfo` pipeline. Absence of the field is represented by an empty list, so all pre-feature server behavior remains byte-for-byte on the existing path.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, Android Media3, Kotlin/JUnit tests, Gradle.

## Global Constraints

- A server without `external_text_sidecar_set_v1` must continue working exactly as today.
- Missing or empty `subtitle.sidecars` must leave the singular selected artifact unchanged.
- Catalog rows without a mounted URL must continue to invoke staged replan.
- Only valid nonnegative SRT/SubRip or VTT/WebVTT sidecar entries may be mounted.
- Cast must not negotiate this local Media3 mounting feature.
- Keep stock Android IME and all previously approved TV UI behavior unchanged.
- Build and install the ARM64 debug TV APK on Shield without launching it.
- Commands assume the repository root is the cwd.

---

### Task 1: Decode the additive sidecar contract and prove old-server compatibility

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackProtocolV3.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackProtocolV3Test.kt`

**Interfaces:**
- Produces: `EXTERNAL_TEXT_SIDECAR_SET_V1_FEATURE`.
- Produces: `PlaybackSubtitleSidecarV3(trackId, index, url, mimeType, format, timingOriginSeconds)`.
- Produces: `PlaybackSubtitleDecisionV3.sidecars: List<PlaybackSubtitleSidecarV3> = emptyList()`.

- [ ] **Step 1: Write the old-server regression test first**

Decode a plan JSON whose subtitle object contains only the existing singular artifact:

```kotlin
val decoded = SiloJson.decodeFromString<PlaybackPlanV3>(
    """{"plan_id":"plan","delivery":"original_http","engine":"media3_direct",
    "stream":{"url":"/stream/session","protocol":"http_progressive"},
    "subtitle":{"mode":"convert","track_id":"file:42:subtitle:0",
    "artifact":{"url":"/stream/session/subtitles/0.vtt","mime_type":"text/vtt","format":"vtt","timing_origin_seconds":0}},
    "decision_reason":"test"}"""
)
assertTrue(decoded.subtitle.sidecars.isEmpty())
assertEquals("/stream/session/subtitles/0.vtt", decoded.subtitle.artifact?.url)
```

- [ ] **Step 2: Write the new-server decode test**

Decode `sidecars` with one SRT entry and assert every field, including combined index and timing origin.

- [ ] **Step 3: Run the shared test and confirm failure**

Run: `./gradlew :shared:testDebugUnitTest --tests '*PlaybackProtocolV3Test*sidecar*'`

Expected: compile failure because `sidecars` does not exist.

- [ ] **Step 4: Add the serializable model with an empty default**

Add:

```kotlin
const val EXTERNAL_TEXT_SIDECAR_SET_V1_FEATURE = "external_text_sidecar_set_v1"

@Serializable
data class PlaybackSubtitleSidecarV3(
    @SerialName("track_id") val trackId: String,
    val index: Int,
    val url: String,
    @SerialName("mime_type") val mimeType: String,
    val format: String,
    @SerialName("timing_origin_seconds") val timingOriginSeconds: Double = 0.0,
)
```

and `val sidecars: List<PlaybackSubtitleSidecarV3> = emptyList()` to `PlaybackSubtitleDecisionV3`.

- [ ] **Step 5: Run and pass both compatibility tests**

Run: `./gradlew :shared:testDebugUnitTest --tests '*PlaybackProtocolV3Test*'`

Expected: PASS, including the singular-artifact-only old-server JSON.

- [ ] **Step 6: Commit the shared contract**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackProtocolV3.kt shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackProtocolV3Test.kt
git commit -m "feat(playback): decode external text sidecar sets"
```

### Task 2: Negotiate the feature only for local Media3 playback

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackProtocolV3.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackCapabilityDetector.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionManager.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerSeekReanchorTest.kt`
- Create: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/cast/CastPlaybackPreparerTest.kt`

**Interfaces:**
- Consumes: `EXTERNAL_TEXT_SIDECAR_SET_V1_FEATURE`.
- Produces: the feature in local `client_features` and local `client_playback_context.features`.
- Preserves: Cast context and Cast requests without the feature.

- [ ] **Step 1: Add failing local-vs-Cast negotiation tests**

Assert the local detected context contains the feature. Capture a normal V3 start request and assert both feature arrays contain it. Capture a Cast start request/context and assert neither feature array contains it.

- [ ] **Step 2: Run tests and confirm failure**

Run: `./gradlew :android-shared:testDebugUnitTest --tests '*PlaybackSessionManagerSeekReanchorTest*' --tests '*CastPlaybackPreparerTest*'`

Expected: local assertions fail because the feature is absent.

- [ ] **Step 3: Add local context negotiation**

Add `EXTERNAL_TEXT_SIDECAR_SET_V1_FEATURE` to `PlaybackCapabilityDetector`'s `contextFeatures`. Do not add it to `chromecastPlaybackContext`.

Add a shared helper:

```kotlin
fun playbackStartClientFeatures(context: ClientPlaybackContext): List<String> =
    if (EXTERNAL_TEXT_SIDECAR_SET_V1_FEATURE in context.features) {
        PLAYBACK_START_CLIENT_FEATURES_V3 + EXTERNAL_TEXT_SIDECAR_SET_V1_FEATURE
    } else {
        PLAYBACK_START_CLIENT_FEATURES_V3
    }
```

Pass `clientFeatures = playbackStartClientFeatures(clientPlaybackContext)` when `PlaybackSessionManager` creates `PlaybackStartRequestV3`.

- [ ] **Step 4: Run and pass negotiation tests**

Run: `./gradlew :android-shared:testDebugUnitTest --tests '*PlaybackSessionManagerSeekReanchorTest*' --tests '*CastPlaybackPreparerTest*'`

Expected: PASS.

- [ ] **Step 5: Commit feature negotiation**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackProtocolV3.kt android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackCapabilityDetector.kt android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionManager.kt android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerSeekReanchorTest.kt android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/cast/CastPlaybackPreparerTest.kt
git commit -m "feat(playback): negotiate external text sidecars"
```

### Task 3: Merge valid sidecars into the existing Media3 mount pipeline

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackV3Session.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackV3SessionTest.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoices.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoicesTest.kt`

**Interfaces:**
- Consumes: `PlaybackSubtitleDecisionV3.sidecars`.
- Produces: one `PlayerSubtitleInfo` per valid combined index with a nonblank URL.
- Preserves: singular-artifact-only output when `sidecars` is empty.

- [ ] **Step 1: Add failing adapter tests**

Add tests covering:

- two valid sidecars become two mountable `PlayerSubtitleInfo` rows;
- a sidecar duplicating the selected singular artifact is deduplicated by index;
- negative index, blank URL, and unsupported `text/x-ssa` entries are ignored;
- `sidecars = emptyList()` returns exactly the existing one selected artifact;
- mode `OFF` can still carry mountable alternatives without selecting one.

- [ ] **Step 2: Run tests and confirm failure**

Run: `./gradlew :android-shared:testDebugUnitTest --tests '*PlaybackV3SessionTest*' :shared:testDebugUnitTest --tests '*PlaybackSubtitleChoicesTest*'`

Expected: sidecars are decoded but not exposed to the session.

- [ ] **Step 3: Implement a pure sidecar mapper**

In `PlaybackV3Session.kt`, map only entries satisfying:

```kotlin
sidecar.index >= 0 && sidecar.url.isNotBlank() &&
    sidecar.format.lowercase() in setOf("srt", "subrip", "vtt", "webvtt") &&
    sidecar.mimeType.lowercase().substringBefore(';') in
        setOf("application/x-subrip", "text/vtt")
```

Create `PlayerSubtitleInfo(index = sidecar.index, codec = sidecar.format, source = "external", url = sidecar.url)` and combine it with the existing selected-artifact row. Deduplicate by index with the sidecar row preferred so its stable external identity and raw URL win.

- [ ] **Step 4: Preserve catalog metadata during the existing merge**

Use `buildPlaybackSubtitleChoices` unchanged where possible. Add only focused assertions that the planned sidecar URL survives while catalog language/title/forced/default metadata are copied onto the row.

- [ ] **Step 5: Run and pass adapter and catalog tests**

Run: `./gradlew :android-shared:testDebugUnitTest --tests '*PlaybackV3SessionTest*' :shared:testDebugUnitTest --tests '*PlaybackSubtitleChoicesTest*'`

Expected: PASS.

- [ ] **Step 6: Commit sidecar mounting data flow**

```bash
git add android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackV3Session.kt android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackV3SessionTest.kt shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoices.kt shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoicesTest.kt
git commit -m "feat(player): mount negotiated external text sidecars"
```

### Task 4: Prove mounted switches are instant and old-server switches still replan

**Files:**
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerStagedReplanTest.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleTransactionAdapterTest.kt`

**Interfaces:**
- Consumes: mounted `PlayerSubtitleInfo` rows from Task 3.
- Preserves: staged replan for catalog rows with blank URLs or absent mounted track IDs.

- [ ] **Step 1: Add the new-server fast-path integration test**

Create a session with two server sidecars and a fake Media3 track graph containing `silo-subtitle:0` and `silo-subtitle:1`. Select index 1 and assert:

```kotlin
assertEquals(1, localSubtitleSelections.size)
assertEquals(0, replanRequests.size)
assertEquals(0, mediaItemReplacements.size)
```

- [ ] **Step 2: Add the mandatory old-server fallback regression test**

Create a plan with only the existing selected artifact at index 0 and a catalog-only index 1 with `url = ""`. Select index 1 and assert:

```kotlin
assertEquals(0, localSubtitleSelections.size)
assertEquals(1, replanRequests.size)
assertEquals(1, stagedPlanPublications.size)
```

Also assert playback position and pause/play state are restored through the existing staged-replan path. This test is the acceptance gate for the user's backward-compatibility requirement.

- [ ] **Step 3: Run both focused paths**

Run: `./gradlew :android-shared:testDebugUnitTest --tests '*PlaybackSessionManagerStagedReplanTest*' :androidTvApp:testDebugUnitTest --tests '*TvSubtitleTransactionAdapterTest*'`

Expected: PASS without changing production fallback logic.

- [ ] **Step 4: Commit compatibility coverage**

```bash
git add android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerStagedReplanTest.kt androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleTransactionAdapterTest.kt
git commit -m "test(player): preserve old-server subtitle replans"
```

### Task 5: Verify, build, and install without launching

**Files:**
- Verify only.

**Interfaces:**
- Produces: an installed Shield debug build with both negotiated and legacy paths covered.

- [ ] **Step 1: Run all Android tests**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Build the ARM64 TV debug APK**

Run: `./gradlew :androidTvApp:assembleDebug`

Expected: BUILD SUCCESSFUL and a debug APK under `androidTvApp/build/outputs/apk/debug/`.

- [ ] **Step 3: Confirm Shield connectivity and ABI**

Run: `adb -s 192.168.1.128:5555 get-state && adb -s 192.168.1.128:5555 shell getprop ro.product.cpu.abi`

Expected: `device` and `arm64-v8a`.

- [ ] **Step 4: Install without launching**

Run: `adb -s 192.168.1.128:5555 install -r androidTvApp/build/outputs/apk/debug/androidTvApp-debug.apk`

Expected: `Success`.

- [ ] **Step 5: Ensure the app remains stopped**

Run: `adb -s 192.168.1.128:5555 shell am force-stop org.siloserver.silo`

Expected: no activity launch command is issued.

- [ ] **Step 6: Inspect final state**

Run: `git status --short --branch && git log --oneline -8`

Expected: clean working tree on local `main`, ahead of `upstream/main` only by intentional commits.
