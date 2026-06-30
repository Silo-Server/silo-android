# Android 7 Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android 7.0/7.1 (API 24/25) support explicit and regression-tested for both Android mobile and Android TV.

**Architecture:** Keep the existing single mobile APK and single TV APK with `minSdk = 24`. Treat API 24/25 as Media3-only playback devices by enforcing the shared MPV device floor before any backend preference can select MPV. Use source/unit policy tests and lint to make the support contract hard to regress.

**Tech Stack:** Kotlin 2.1.20, Kotlin Multiplatform Android targets, Jetpack Compose, Compose for TV, Media3 1.10.0, optional `dev.jdtech.mpv:libmpv:1.0.0` behind API/ABI gates, `kotlin.test`, Android lint.

## Global Constraints

- Android mobile and Android TV must support Android 7.0 and 7.1, API 24 and API 25.
- `:androidApp`, `:androidTvApp`, `:android-shared`, and `:shared` keep `minSdk = 24`.
- Do not make ebooks available on Android TV.
- Do not make the MPV backend run on Android 7.
- Do not create a separate legacy APK flavor for this pass.
- Do not reduce `targetSdk = 35` or `compileSdk = 36`.
- API 24/25 devices must never instantiate MPV or select the MPV backend.
- Requests, admin, and watch-together code may remain present internally, but they must stay off user menus.

---

## File Structure

- `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/backend/MpvDeviceFloor.kt` owns the pure API/ABI capability decision for whether MPV can be used on this device.
- `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelector.kt` owns the backend decision for a playback request. It must honor the device floor before user or automatic MPV preference.
- `android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/backend/MpvDeviceFloorTest.kt` pins API 24/25 as unsupported and modern 64-bit devices as supported.
- `android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelectorTest.kt` pins the selector fallback behavior when MPV is unsupported.
- `androidApp/src/androidUnitTest/kotlin/com/continuum/app/android/AndroidManifestPolicyTest.kt` pins mobile build/manifest policy for Android 7 support and the MPV manifest override.
- `androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/TvAndroidManifestPolicyTest.kt` pins TV build/manifest policy for Android 7 support and the MPV manifest override.
- `README.md` documents that Android 7 is supported and MPV is API 26+ only.

---

### Task 1: Pin The Android 7 MPV Device Floor

**Files:**
- Modify: `android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/backend/MpvDeviceFloorTest.kt`
- Verify: `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/backend/MpvDeviceFloor.kt`

**Interfaces:**
- Consumes: `MpvDeviceFloor.isMpvSupported(sdkInt: Int, supportedAbis: List<String>): Boolean`
- Produces: Regression coverage proving API 24 and API 25 return `false`.

- [ ] **Step 1: Replace `MpvDeviceFloorTest.kt` with explicit Android 7 coverage**

```kotlin
package com.continuum.app.common.player.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvDeviceFloorTest {
    @Test
    fun supportedOnModern64BitDevice() {
        assertTrue(MpvDeviceFloor.isMpvSupported(sdkInt = 30, supportedAbis = listOf("arm64-v8a")))
        assertTrue(MpvDeviceFloor.isMpvSupported(sdkInt = 30, supportedAbis = listOf("x86_64")))
    }

    @Test
    fun unsupportedOnAndroid7RegardlessOfAbi() {
        val android7Sdks = listOf(24, 25)
        val abiSets = listOf(
            listOf("arm64-v8a"),
            listOf("x86_64"),
            listOf("armeabi-v7a"),
            listOf("arm64-v8a", "armeabi-v7a"),
        )

        android7Sdks.forEach { sdk ->
            abiSets.forEach { abis ->
                assertFalse(
                    MpvDeviceFloor.isMpvSupported(sdkInt = sdk, supportedAbis = abis),
                    "sdk=$sdk abis=$abis must stay Media3-only",
                )
            }
        }
    }

    @Test
    fun unsupportedOn32BitOnlyDevice() {
        assertFalse(MpvDeviceFloor.isMpvSupported(sdkInt = 30, supportedAbis = listOf("armeabi-v7a")))
    }

    @Test
    fun minimumSdkConstantDocumentsLibmpvFloor() {
        assertEquals(26, MpvDeviceFloor.MIN_SDK_FOR_MPV)
    }
}
```

- [ ] **Step 2: Run the focused device-floor test**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest --tests "com.continuum.app.common.player.backend.MpvDeviceFloorTest"
```

Expected: PASS. This is a regression pin for behavior that should already be true in `MpvDeviceFloor`.

- [ ] **Step 3: Commit the regression pin**

```bash
git add android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/backend/MpvDeviceFloorTest.kt
git commit -m "test: pin Android 7 MPV device floor"
```

---

### Task 2: Force Media3 When MPV Is Unsupported

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelector.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelectorTest.kt`

**Interfaces:**
- Consumes: `VideoPlaybackBackendRequest.mpvSupportedOnDevice: Boolean`
- Produces: `VideoPlaybackBackendSelector.select(request: VideoPlaybackBackendRequest): VideoPlaybackBackendKind` returns `Media3` whenever `mpvSupportedOnDevice == false`.

- [ ] **Step 1: Add selector tests for unsupported devices**

Add these tests to `VideoPlaybackBackendSelectorTest` after `explicitMpvPreferenceWins()`:

```kotlin
    @Test
    fun explicitMpvPreferenceFallsBackToMedia3WhenDeviceUnsupported() {
        val request = VideoPlaybackBackendRequest(
            preference = VideoPlaybackBackendPreference.Mpv,
            mpvSupportedOnDevice = false,
        )

        assertEquals(VideoPlaybackBackendKind.Media3, VideoPlaybackBackendSelector.select(request))
    }

    @Test
    fun plannedMpvDirectFallsBackToMedia3WhenDeviceUnsupported() {
        val request = VideoPlaybackBackendRequest(
            playMethod = PlayMethod.DIRECT,
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            plannedEngine = PlaybackEngineKind.MPV_DIRECT,
            hasHardContainer = true,
            hasStyledSubtitles = true,
            mpvSupportedOnDevice = false,
        )

        assertEquals(VideoPlaybackBackendKind.Media3, VideoPlaybackBackendSelector.select(request))
    }
```

- [ ] **Step 2: Run the selector tests and confirm the explicit preference test fails**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest --tests "com.continuum.app.common.player.backend.VideoPlaybackBackendSelectorTest"
```

Expected: FAIL on `explicitMpvPreferenceFallsBackToMedia3WhenDeviceUnsupported` because the current selector lets explicit MPV preference win before the device floor.

- [ ] **Step 3: Replace `VideoPlaybackBackendSelector.select` with device-floor-first logic**

Replace the body of `VideoPlaybackBackendSelector.kt` with:

```kotlin
package com.continuum.app.common.player.backend

import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind

object VideoPlaybackBackendSelector {
    fun select(request: VideoPlaybackBackendRequest): VideoPlaybackBackendKind =
        when {
            // Device floor is absolute: API 24/25 must not select or instantiate MPV.
            !request.mpvSupportedOnDevice -> VideoPlaybackBackendKind.Media3
            request.preference == VideoPlaybackBackendPreference.Media3 -> VideoPlaybackBackendKind.Media3
            request.preference == VideoPlaybackBackendPreference.Mpv -> VideoPlaybackBackendKind.Mpv
            else -> when {
                // Route/session intent: ExoPlayer is the correct engine here.
                request.isCasting -> VideoPlaybackBackendKind.Media3
                request.isDrmProtected -> VideoPlaybackBackendKind.Media3
                request.isExternalDisplay -> VideoPlaybackBackendKind.Media3
                request.isAdaptiveHlsStream -> VideoPlaybackBackendKind.Media3
                request.delivery == PlaybackDelivery.SERVER_REMUX_HLS -> VideoPlaybackBackendKind.Media3
                request.delivery == PlaybackDelivery.SERVER_TRANSCODE_HLS -> VideoPlaybackBackendKind.Media3
                request.plannedEngine == PlaybackEngineKind.MEDIA3_DIRECT -> VideoPlaybackBackendKind.Media3
                request.plannedEngine == PlaybackEngineKind.MEDIA3_PROGRESSIVE_REMUX -> VideoPlaybackBackendKind.Media3
                request.plannedEngine == PlaybackEngineKind.MEDIA3_HLS -> VideoPlaybackBackendKind.Media3
                request.plannedEngine == PlaybackEngineKind.MPV_DIRECT -> VideoPlaybackBackendKind.Mpv
                request.delivery == PlaybackDelivery.SERVER_REMUX_PROGRESSIVE -> VideoPlaybackBackendKind.Mpv
                request.playMethod == PlayMethod.TRANSCODE -> VideoPlaybackBackendKind.Media3
                // Fidelity: MPV for hard containers / styled subtitles on supported devices.
                request.hasHardContainer -> VideoPlaybackBackendKind.Mpv
                request.hasStyledSubtitles -> VideoPlaybackBackendKind.Mpv
                else -> VideoPlaybackBackendKind.Media3
            }
        }
}
```

- [ ] **Step 4: Run the selector tests and confirm they pass**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest --tests "com.continuum.app.common.player.backend.VideoPlaybackBackendSelectorTest"
```

Expected: PASS.

- [ ] **Step 5: Commit the selector behavior**

```bash
git add android-shared/src/androidMain/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelector.kt \
        android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelectorTest.kt
git commit -m "fix: force Media3 below MPV device floor"
```

---

### Task 3: Pin Mobile And TV Android 7 Build Policy

**Files:**
- Modify: `androidApp/src/androidUnitTest/kotlin/com/continuum/app/android/AndroidManifestPolicyTest.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/TvAndroidManifestPolicyTest.kt`

**Interfaces:**
- Consumes: app `build.gradle.kts` and `src/androidMain/AndroidManifest.xml` source text.
- Produces: policy tests that fail if mobile or TV drops Android 7 install support or removes the MPV override contract.

- [ ] **Step 1: Replace the mobile manifest policy test**

Use this complete file for `androidApp/src/androidUnitTest/kotlin/com/continuum/app/android/AndroidManifestPolicyTest.kt`:

```kotlin
package com.continuum.app.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidManifestPolicyTest {
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()
    private val buildFile = File("build.gradle.kts").readText()

    @Test
    fun mobileDisablesAndroidBackupForTokenAndProfileState() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertFalse(manifest.contains("""android:allowBackup="true""""))
    }

    @Test
    fun mobileKeepsAndroid7InstallFloor() {
        assertTrue(buildFile.contains("minSdk = 24"))
        assertTrue(buildFile.contains("targetSdk = 35"))
        assertTrue(buildFile.contains("compileSdk = 36"))
    }

    @Test
    fun mobileDocumentsMpvOverrideForAndroid7Media3Fallback() {
        assertTrue(manifest.contains("""tools:overrideLibrary="dev.jdtech.mpv""""))
        assertTrue(manifest.contains("libmpv declares minSdk 26"))
        assertTrue(manifest.contains("uses Media3 on Android 7/API 24-25"))
    }
}
```

- [ ] **Step 2: Replace the TV manifest policy test**

Use this complete file for `androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/TvAndroidManifestPolicyTest.kt`:

```kotlin
package com.continuum.app.tv

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAndroidManifestPolicyTest {
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()
    private val buildFile = File("build.gradle.kts").readText()

    @Test
    fun tvDisablesAndroidBackupForTokenAndProfileState() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertFalse(manifest.contains("""android:allowBackup="true""""))
    }

    @Test
    fun tvKeepsAndroid7InstallFloor() {
        assertTrue(buildFile.contains("minSdk = 24"))
        assertTrue(buildFile.contains("targetSdk = 35"))
        assertTrue(buildFile.contains("compileSdk = 36"))
    }

    @Test
    fun tvDocumentsMpvOverrideForAndroid7Media3Fallback() {
        assertTrue(manifest.contains("""tools:overrideLibrary="dev.jdtech.mpv""""))
        assertTrue(manifest.contains("libmpv declares minSdk 26"))
        assertTrue(manifest.contains("uses Media3 on Android 7/API 24-25"))
    }
}
```

- [ ] **Step 3: Run the mobile and TV policy tests**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.continuum.app.android.AndroidManifestPolicyTest" \
          :androidTvApp:testDebugUnitTest --tests "com.continuum.app.tv.TvAndroidManifestPolicyTest"
```

Expected: PASS.

- [ ] **Step 4: Commit the build policy tests**

```bash
git add androidApp/src/androidUnitTest/kotlin/com/continuum/app/android/AndroidManifestPolicyTest.kt \
        androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/TvAndroidManifestPolicyTest.kt
git commit -m "test: pin Android 7 app build policy"
```

---

### Task 4: Document The Android 7 Runtime Contract

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: approved design in `docs/superpowers/specs/2026-06-30-android-7-support-design.md`.
- Produces: README text that states Android 7 support and MPV API 26+ gating.

- [ ] **Step 1: Update the SDK row in the README tech table**

Replace:

```markdown
| **SDK** | minSdk 24 · targetSdk 35 · compileSdk 36 · JDK 21 |
```

With:

```markdown
| **SDK** | Android 7.0+ / minSdk 24 · targetSdk 35 · compileSdk 36 · JDK 21 |
```

- [ ] **Step 2: Add an Android 7 compatibility paragraph after the server paragraph**

After:

```markdown
The clients talk to a Silo server over its `/api/v1/*` REST + WebSocket API. The server owns the library, scanning, metadata, transcoding decisions, and auth; the clients render it and drive playback.
```

Add:

```markdown
Android 7.0 and 7.1 (API 24/25) are supported on both phone and Android TV. Those devices use the Media3 playback path; the optional MPV backend remains gated to API 26+ devices because the bundled `dev.jdtech.mpv:libmpv` artifact declares a higher runtime floor.
```

- [ ] **Step 3: Run a focused docs/policy source check**

Run:

```bash
rg -n "Android 7.0 and 7.1|Android 7.0\\+ / minSdk 24|MPV backend remains gated to API 26" README.md
```

Expected: three matching lines in `README.md`.

- [ ] **Step 4: Commit the README update**

```bash
git add README.md
git commit -m "docs: document Android 7 support"
```

---

### Task 5: Run Full Compatibility Verification

**Files:**
- No source files are modified in this task.

**Interfaces:**
- Consumes: committed changes from Tasks 1 through 4.
- Produces: fresh verification evidence for unit tests, app builds, and Android lint.

- [ ] **Step 1: Run all debug unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build both debug APKs**

```bash
./gradlew :androidApp:assembleDebug :androidTvApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run lint against both clients**

```bash
./gradlew :androidApp:lintDebug :androidTvApp:lintDebug
```

Expected: `BUILD SUCCESSFUL` and lint reports written under:

```text
androidApp/build/reports/lint-results-debug.html
androidTvApp/build/reports/lint-results-debug.html
```

- [ ] **Step 4: Inspect final git state**

```bash
git status --short --branch
git log --oneline -5
```

Expected: clean branch, with the Task 1 through Task 4 commits present above the plan/spec commits.

- [ ] **Step 5: Push after verification**

```bash
git push origin main
```

Expected: `main -> main` push succeeds.

---

## Self-Review

- Spec coverage: Task 1 pins API 24/25 device floor; Task 2 enforces Media3 selection below the MPV floor; Task 3 pins mobile and TV app floors/manifests; Task 4 documents Android 7 support and MPV API 26+ gating; Task 5 verifies tests, builds, and lint.
- Android TV ebooks exclusion is preserved by not touching TV reading/library feature gates.
- Requests/admin/watch-together menu visibility is preserved by not touching navigation/menu files.
- Type consistency: `mpvSupportedOnDevice`, `VideoPlaybackBackendPreference.Mpv`, `PlaybackEngineKind.MPV_DIRECT`, `PlaybackDelivery.ORIGINAL_HTTP`, and `MpvDeviceFloor.MIN_SDK_FOR_MPV` are existing names in the current codebase.
