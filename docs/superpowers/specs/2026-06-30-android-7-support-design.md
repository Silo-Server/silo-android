# Android 7 Support Design

## Goal

Silo Android mobile and Silo Android TV must support Android 7.0 and 7.1
(API 24 and API 25) as first-class install targets. Users on Android 7 should
be able to sign in, browse libraries, open details, use profiles, play supported
media, read/download mobile ebooks, and use TV/mobile app surfaces that are
otherwise available to their client type.

The project already declares `minSdk = 24` for `:androidApp`,
`:androidTvApp`, `:android-shared`, and `:shared`. This work turns that declared
floor into an explicit runtime contract, especially around playback.

## Non-Goals

- Do not make ebooks available on Android TV.
- Do not make the MPV backend run on Android 7.
- Do not create a separate legacy APK flavor unless the compatibility pass
  proves it is necessary.
- Do not reduce target SDK or compile SDK.

## Compatibility Contract

Both client APKs remain installable on API 24 and API 25.

On API 24 and API 25, playback must never instantiate the MPV player or select
the MPV backend. The bundled `dev.jdtech.mpv:libmpv` artifact declares a
library floor of API 26, so API 24/25 devices use Media3-backed playback only.
The existing manifest override may remain, but it is safe only if all runtime
MPV entry points are guarded by the backend floor.

On API 26 and newer, the existing MPV selection rules may continue to apply.
The Android 7 work should not remove MPV for supported newer devices.

## Playback Behavior

The backend selection path must treat API 24/25 as `mpvSupportedOnDevice =
false`, regardless of ABI or user preference. If a direct/original playback path
would normally choose MPV, Android 7 should stay on Media3 and rely on the
server-compatible route selected by the existing playback request policy.

Required behavior:

- Mobile video playback starts through Media3 on Android 7.
- TV video playback starts through Media3 on Android 7.
- Audiobook playback remains Media3-compatible on Android 7.
- Subtitle, audio-track, and progress/reporting controls must still operate
  through the shared Media3 session path.
- If a future setting exposes explicit MPV selection, API 24/25 must still
  refuse MPV and fall back to Media3.

## Other Surfaces

The Android 7 compatibility pass should review and preserve these areas:

- Initial login and QR/device-login flows.
- Profile picker, profile creation, avatar editing, and profile quality
  settings.
- Home, library, search, detail, people, downloads, and settings screens.
- Mobile ebook reader and downloadable ebook/video/audio files.
- TV media browsing, detail pages, player controls, and Watch Next plumbing.

Requests, admin, and watch-together code may remain present internally, but they
must stay off user menus until product approval makes those surfaces visible.

## Implementation Shape

Keep the current single mobile APK and single TV APK. Strengthen the existing
shared playback floor instead of creating legacy variants.

Primary changes should be small and testable:

- Centralize Android 7 playback decisions in the existing backend floor or
  selector path.
- Add source or unit tests that assert API 24/25 cannot select MPV.
- Add manifest/build policy tests that assert both app modules remain
  `minSdk = 24`.
- Run Android lint for both clients so unguarded newer platform APIs are caught.
- Update README/docs to state the Android 7 support contract and MPV API 26+
  gate.

## Testing

Minimum verification for implementation:

- `./gradlew testDebugUnitTest`
- `./gradlew :androidApp:assembleDebug :androidTvApp:assembleDebug`
- `./gradlew :androidApp:lintDebug :androidTvApp:lintDebug`

Add focused tests for:

- `MpvDeviceFloor` rejecting API 24 and API 25.
- Backend selection refusing MPV when device support is false, even when the
  media would otherwise prefer MPV.
- Mobile and TV build files keeping `minSdk = 24`.
- Mobile and TV manifests retaining the MPV override comment/contract if the
  override remains necessary.

Device/emulator smoke testing should include at least one API 24 or API 25
mobile emulator and one Android TV emulator/device as close to API 24/25 as the
available SDK images allow. If Android TV API 24/25 emulator images are not
available locally, run the static/lint/test checks and document the emulator gap.

## Risks

The largest risk is native library loading: if any API 24/25 code path loads or
instantiates MPV despite the backend floor, the app can crash even though the
APK installs. The second risk is assuming Media3 can play every original file
that MPV handles on newer devices; Android 7 must be allowed to use server
fallbacks/remuxes when original playback is not safe.

The fallback design intentionally favors stable playback over original-file
purity on Android 7. Newer devices keep the richer MPV path.
