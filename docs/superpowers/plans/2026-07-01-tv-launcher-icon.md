# Android TV Launcher Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Android TV adaptive launcher foreground with a transparent Silo mark that renders cleanly under Google TV's circular launcher mask.

**Architecture:** Android TV keeps the existing adaptive-icon XML and background color. Adaptive foreground density PNGs change from a baked rounded-square tile to mark-only transparent assets. Legacy TV launcher PNGs also become circle-friendly because the Google TV Streamer app row can render/mask that path.

**Tech Stack:** Android adaptive icons, PNG density assets, ImageMagick, Kotlin/JVM source tests, Gradle.

## Global Constraints

- Work in `/Users/jimcole/projects/personal/silo/core/silo-android`.
- Do not change mobile launcher icons.
- Do not remove legacy `ic_launcher.png` fallback assets; regenerate them as circle-friendly TV icons.
- Verify on Google TV Streamer `61071HFAG1FWQX`.

---

### Task 1: Generate Mark-Only Adaptive Foreground Assets

**Files:**
- Modify: `androidTvApp/src/androidMain/res/mipmap-mdpi/ic_launcher_foreground.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-hdpi/ic_launcher_foreground.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-xhdpi/ic_launcher_foreground.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-xxhdpi/ic_launcher_foreground.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher_foreground.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-mdpi/ic_launcher.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-hdpi/ic_launcher.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-xhdpi/ic_launcher.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-xxhdpi/ic_launcher.png`
- Modify: `androidTvApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png`

**Interfaces:**
- Consumes: Existing `androidTvApp/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml` foreground reference.
- Produces: Transparent foreground PNGs with centered Silo mark only.

- [ ] **Step 1: Generate a 432px transparent master foreground**

Use ImageMagick to draw the Silo mark on a transparent canvas. The mark should fit within the adaptive icon safe zone and leave transparent pixels at all edges.

- [ ] **Step 2: Resize the master into density PNGs**

Generate `108`, `162`, `216`, `324`, and `432` pixel foreground PNGs for mdpi through xxxhdpi.

- [ ] **Step 3: Generate circle-friendly legacy icons**

Composite the same centered mark over a blue circular field and resize to `48`, `72`, `96`, `144`, and `192` pixel legacy icon PNGs.

- [ ] **Step 4: Inspect the xxxhdpi foreground and legacy icon**

Run: `sips -g pixelWidth -g pixelHeight androidTvApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher_foreground.png`

Expected: `pixelWidth: 432` and `pixelHeight: 432`.

Run: `sips -g pixelWidth -g pixelHeight androidTvApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png`

Expected: `pixelWidth: 192` and `pixelHeight: 192`.

### Task 2: Update Launcher Icon Guard Test

**Files:**
- Modify: `androidTvApp/src/androidUnitTest/kotlin/com/continuum/app/tv/ui/theme/TvLauncherIconAssetsTest.kt`

**Interfaces:**
- Consumes: Generated adaptive foreground PNG.
- Produces: Test coverage that prevents reintroducing a baked tile into the adaptive foreground.

- [ ] **Step 1: Replace the old centered-tile assertion**

Assert that adaptive foreground corners/edges are transparent, the adaptive opaque bounding box is centered inside a safe-zone range, and the legacy TV launcher icon has transparent corners with an opaque center field.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :androidTvApp:testDebugUnitTest --tests com.continuum.app.tv.ui.theme.TvLauncherIconAssetsTest`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Build, Install, And Visual Verify

**Files:**
- Build output: `androidTvApp/build/outputs/apk/debug/androidTvApp-universal-debug.apk`

**Interfaces:**
- Consumes: Updated TV launcher resources.
- Produces: Installed APK and launcher screenshot evidence.

- [ ] **Step 1: Build the TV APK**

Run: `./gradlew :androidTvApp:assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install to Google TV Streamer**

Run: `adb -s 61071HFAG1FWQX install -r androidTvApp/build/outputs/apk/debug/androidTvApp-universal-debug.apk`

Expected: `Success`.

- [ ] **Step 3: Capture launcher screenshot**

Run: `adb -s 61071HFAG1FWQX exec-out screencap -p > /tmp/google-streamer-icon-after.png`

Expected: screenshot shows Silo as a centered mark on the launcher mask, not a cropped rounded-square tile.
