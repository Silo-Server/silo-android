# Task 2 Report: Force Media3 When MPV Is Unsupported

Date: 2026-06-30

## Scope

Changed only:
- `android-shared/src/androidMain/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelector.kt`
- `android-shared/src/androidUnitTest/kotlin/com/continuum/app/common/player/backend/VideoPlaybackBackendSelectorTest.kt`

## What Changed

- Added two regression tests covering unsupported-device fallback:
  - explicit MPV preference with `mpvSupportedOnDevice = false`
  - planned `MPV_DIRECT` with `mpvSupportedOnDevice = false`
- Moved the device-floor check ahead of explicit MPV preference in `VideoPlaybackBackendSelector.select(...)`.
- Result: any request with `mpvSupportedOnDevice == false` now returns `VideoPlaybackBackendKind.Media3`.

## RED

Command:

```bash
./gradlew :android-shared:testDebugUnitTest --tests "com.continuum.app.common.player.backend.VideoPlaybackBackendSelectorTest"
```

Result:

```text
19 tests completed, 1 failed
VideoPlaybackBackendSelectorTest > explicitMpvPreferenceFallsBackToMedia3WhenDeviceUnsupported FAILED
java.lang.AssertionError at VideoPlaybackBackendSelectorTest.kt:35
```

This is the expected failure from the pre-fix selector, where explicit MPV preference still won on unsupported devices.

## GREEN

Command:

```bash
./gradlew :android-shared:testDebugUnitTest --tests "com.continuum.app.common.player.backend.VideoPlaybackBackendSelectorTest"
```

Result:

```text
BUILD SUCCESSFUL in 2s
```

## Commit

- `51be27d` - `fix: force Media3 below MPV device floor`

## Test Summary

Selector regression tests passed after the device-floor-first selector change.

