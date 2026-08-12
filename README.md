# Silo Android

Android **phone** and **Android TV** clients for the [Silo](https://github.com/Silo-Server/silo-server) self-hosted media server — stream and download your movies, shows, music, audiobooks, and ebooks, with quality-aware playback and multi-server/multi-profile support.

Built as a Kotlin Multiplatform project: one shared business-logic core, two Jetpack Compose apps (touch + 10-foot TV). This branch uses the full Silo namespace cut: Kotlin packages live under `org.siloserver.silo`, and both apps share a single application ID `org.siloserver.silo` so they publish as one Google Play listing (Play routes each build by manifest feature filtering). Installs under legacy IDs do not upgrade in place; users should expect a fresh app install, sign-in, and offline media download.

> **Status:** WIP (`v0.2.x`). The architecture is solid and the feature surface is broad; some areas are intentionally "bones-level" and under active redesign (see [Roadmap](#roadmap)).
>
> **Current exposure note:** Requests is live on both Android surfaces, gated by the server's `requests_enabled` flag (`/api/v1/requests/status`), and reached from the profile menu and search — matching the Apple clients. The admin stats dashboard is live for acting admins via Settings (Apple's dashboard design). The richer admin screens (users/sessions/logs/scans) and Watch Together remain inaccessible.

---

## Table of contents

- [What's inside](#whats-inside)
- [Install APKs](#install-apks)
- [Feature overview](#feature-overview)
- [Architecture](#architecture)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Testing](#testing)
- [Conventions](#conventions)
- [Roadmap](#roadmap)
- [License](#license)

---

## What's inside

| | |
|---|---|
| **Apps** | Android phone · Android TV |
| **Application ID** | `org.siloserver.silo` (shared by phone + TV — one Play listing) |
| **Language / UI** | Kotlin 2.1.20 · Jetpack Compose (Material 3) · Compose for TV (`androidx.tv`) |
| **Playback** | AndroidX **Media3 / ExoPlayer** 1.10.1 with a pinned FFmpeg audio fallback extension |
| **Networking** | **Ktor** 3.1.2 client · kotlinx.serialization · WebSockets for realtime |
| **DI** | **Koin** 4.1.0 |
| **Persistence** | AndroidX DataStore · EncryptedSharedPreferences (tokens) · WorkManager (downloads) |
| **Diagnostics** | Native bounded capture · local review/consent · hosted default or self-hosted upload |
| **Images** | Coil 3 (Ktor-backed) |
| **SDK** | Android 7.0+ / minSdk 24 · targetSdk 36 · compileSdk 36 · JDK 21 |

The clients talk to a Silo server over its `/api/v1/*` REST + WebSocket API. The server owns the library, scanning, metadata, transcoding decisions, and auth; the clients render it and drive playback.

Android 7.0 and 7.1 (API 24/25) are supported on both phone and Android TV. Video and audiobook playback use the same Media3 service on every supported Android version.

---

## Install APKs

Latest debug APKs are published on each tagged release.

| App | Downloader code | Downloader link | Direct APK |
|---|---:|---|---|
| Android | `1051382` | <http://aftv.news/1051382> | <https://github.com/Silo-Server/silo-android/releases/latest/download/silo-android-latest-universal-debug.apk> |
| Android TV | `1636227` | <http://aftv.news/1636227> | <https://github.com/Silo-Server/silo-android/releases/latest/download/silo-android-tv-latest-universal-debug.apk> |

Install **Downloader by AFTVnews** on Android TV / Google TV, enter the code for the app you want, then allow APK installs from Downloader when prompted.

---

## Feature overview

A condensed tour. For the exhaustive, per-feature checklist (with phone/TV coverage and file pointers) see **[FEATURES.md](FEATURES.md)**.

### 📺 Playback (phone + TV)
- **Adaptive delivery** — the server picks **Direct Play**, **Remux**, or **Transcode** from the device's real capabilities; the client can also fall back to transcode at runtime if a track turns out to be undecodable (e.g. an AV receiver is unplugged mid-stream).
- **Deep capability detection** — enumerates hardware decoders (H.264, HEVC, AV1, VP9, **Dolby Vision** profiles 5/7/8), panel HDR (HDR10, HDR10+, HLG, DV), and audio-sink passthrough (**E-AC3 JOC/Atmos, TrueHD, DTS-HD**); an optional bundled **FFmpeg** audio decoder fills gaps for lossless codecs.
- **Quality-of-life** — staged playback buffering, per-content **refresh-rate matching** (phone), HDR toggle, intro auto-skip, chapters, sleep timer, playback speed, configurable video gravity (fit/fill/stretch).
- **Tracks & subtitles** — audio-track switching (incl. mid-stream), subtitle selection with **styling, position, and sync offset**, plus a subtitle suite: provider **search & download** and **AI transcription/translation** with quota tracking.
- **System integration** — single Media3 `MediaSession` powers lock-screen / notification / headset / Assistant controls; TV adds D-pad transport, an info HUD, a chapter scrubber, and HDMI EDID-driven display-mode selection.

### Watch Together
Not currently exposed on Android phone or Android TV. Shared sync infrastructure and design notes may exist in the repository, but users cannot create or join Watch Together sessions from the apps on this branch.

### ⬇️ Offline & Downloads (phone)
WorkManager-backed downloads of video, audiobooks, and books to public device storage (scoped `MediaStore` on API 30+), preserving original filenames/formats so other apps can discover and open them. Metadata lives in Room, local playback/read paths work without a server session, and the app can boot straight to Downloads when launched offline. Download monitoring supports subscription-style queues and **Reclaim Watched** cleanup for completed items that have been watched/read/listened.

### 📚 Library, browse & discovery (phone + TV)
- **Phone navigation** — Home, Libraries, For You, Calendar, and Downloads when the active profile has downloads. Video / Audio / Reading are library modes, not bottom-nav tabs.
- **TV navigation** — Home, visible media-type tabs derived from server libraries (Movies/TV/Music/Audiobooks), and Calendar. Reading/ebooks are intentionally excluded from TV.
- **Home** with Continue Watching, Recently Added/Released, and server-curated recommendation rows.
- **Browse** with genre/rating filters, sorting, and infinite-scroll grids; **collections** are browse-only in the Android clients, while collection authoring/management remains web-only.
- **Item detail** for movies and series includes seasons → episodes, multi-version files, cast/crew, local download controls, and phone-to-TV playback handoff.
- **Search** scoped by media type, debounced and paginated.
- **Requests** — live on phone and TV behind the server's `requests_enabled` flag (profile menu + search). **Admin** — stats dashboard only, role-gated in Settings. **Not exposed** — full admin management and Watch Together are not reachable app surfaces today.

### 📖 Reading & 🎧 Audio
- **Ebook reader (phone only)** — EPUB, PDF, CBZ (comics), TXT/Markdown, FB2/FBZ, plus MOBI/AZW/AZW3 when the server can convert to EPUB; CBR and unsupported originals can be downloaded/opened externally. Themes, text size, margins, table of contents, bookmarks, and progress are supported.
- **Audiobook player (phone + TV)** — cover/metadata, chapters, resume, playback speed, sleep timer (incl. end-of-chapter), and bookmarks, sharing the same Media3 engine as video. TV has a dedicated ten-foot audiobook detail/player flow.

### SiloControl (phone + TV)
Android phone can discover SiloCast receivers on the local network, launch movies/episodes on TV with the selected file/track/resume context, and act as a lightweight remote for play/pause, seek, quality, audio, and subtitle changes. TV advertises the local SiloCast receiver only while authenticated and foregrounded. The channel is TLS-PSK and wire-compatible with the Apple clients' SiloControl protocol (same `_silocast._tcp` service, hello/serverId authorization, heartbeat), so Android phones can cast to Apple TVs and iPhones to Android TVs.

### 🔔 Personalization & engagement (phone + TV)
Multiple **household profiles** per account (PINs, child profiles, content-rating limits, per-profile language/subtitle prefs), favorites & watchlist, ratings, a release **calendar**, and an in-app **notifications inbox** with realtime updates. Android push has a guarded client-side registration/data-message path, but real FCM delivery requires server provider support plus Firebase configuration in the phone app. TV mirrors continue-watching into the system **Watch Next** row.

### 🌐 Multi-server & accounts (phone + TV)
Add and switch between multiple Silo servers (encrypted per-server token slots), use username/password or device/QR sign-in, and manage household profiles. Admin screens are not currently exposed in the Android apps.

### Client diagnostics (phone + TV)
Android-native diagnostics can retain a bounded, redacted local report for crashes, ANRs, playback, networking, focus, cast, downloads, and lifecycle events. A two-segment journal keeps only curated, already-redacted lifecycle breadcrumbs so next-launch ANR/native-crash reports retain pre-exit context; identity transitions rotate it, and Never/sign-out purges it. Adult profiles can review and delete account-scoped reports on-device, choose consent, or run a timed diagnostic capture. Child profiles cannot capture, review, or upload reports.

The default destination is Silo's hosted collector at `diagnostics.siloserver.org`; self-hosted Silo ingest remains an explicit compatibility choice. Hosted collection is manual/Ask-only, verifies the live collector identity before a new capture, and re-attests the authenticated source-server account before a first upload. Self-hosted collection supports Ask / Always / Never when the originating server advertises diagnostics support. Reports are never retargeted across destination, server, account, or profile boundaries. Profile transitions close the capture gate and rotate live evidence without discarding retained account reports; sign-out, server removal, and Never consent purge the applicable evidence. One-off manual reports remain available under Never without enabling persistent capture.

This feature does not use Sentry, GlitchTip, Crashlytics, OpenTelemetry, ACRA, or another hosted observability SDK. Crash-time work is local and bounded; exact credential values receive a bounded replacement before the app-private marker is written, structural redaction runs during next-launch report assembly, and archive construction and upload occur after restart. Hosted reports preserve the exact application version, build number, and OS version; privacy filtering targets server network identity, account or personal identity, and credentials rather than release metadata.

---

## Architecture

Three library layers under two app shells. Dependencies only point downward.

```
┌──────────────────────────┐   ┌──────────────────────────┐
│        :androidApp        │   │       :androidTvApp       │
│  Phone UI (Compose M3)    │   │  TV UI (Compose for TV)   │
│  MainActivity + nav graph │   │ MainTvActivity + top menu │
└─────────────┬─────────────┘   └─────────────┬────────────┘
              └───────────────┬───────────────┘
                  ┌───────────▼────────────┐
                  │     :android-shared      │  Android-only:
                  │  Media3 player + service │  ExoPlayer, capability
                  │  downloads, DataStore,   │  probes, MediaAuth,
                  │  player DI               │  settings stores
                  └───────────┬──────────────┘
                  ┌───────────▼──────────────┐
                  │         :shared           │  KMP commonMain:
                  │  Ktor client + APIs,      │  repositories, shared
                  │  repositories, models,    │  ViewModels, RoomSync
                  │  ViewModels, DI           │  engine, ApiResult
                  └───────────────────────────┘
```

### Modules
- **`shared`** (KMP, `commonMain`) — the cross-platform core: Ktor `HttpClient`, typed API classes, repositories, most ViewModels, domain models, and the `ApiResult` type.
- **`android-shared`** — Android-only infrastructure shared by both apps: the Media3 `SiloPlaybackService`, player/backend factory, capability probes, the stream-auth OkHttp interceptor, public downloads, DataStore-backed settings, and native diagnostics capture/storage/upload coordination.
- **`androidApp`** — the phone app: Compose Material 3 screens, bottom-nav shell, `MainActivity`.
- **`androidTvApp`** — the TV app: Compose for TV, tvOS-aligned top-menu shell, D-pad focus, `MainTvActivity`, plus TV-only Watch Next integration.

### Networking & auth
- A single Ktor `HttpClient` (OkHttp engine) with content negotiation, WebSockets, timeouts, and a custom **auth plugin** that attaches `Bearer` + profile headers, rewrites relative `/api/*` paths to the active server, and performs **single-flight token refresh** on `401`.
- Media streams (HLS/progressive) are fetched by ExoPlayer **outside** Ktor, so a parallel **`MediaAuthInterceptor`** (OkHttp) mirrors the same token-attach / refresh semantics for stream URLs.
- **`ServerRegistry`** + **`TokenManager`** provide multi-server support with isolated, encrypted per-server token slots. In-memory implementations live in `shared`; the Android apps override them with `EncryptedSharedPreferences`-backed versions via DI.
- Typed API classes (`AuthApi`, `CatalogApi`, `PlaybackApi`, `EbookReaderApi`, `NotificationsApi`, and others) wrap endpoints and return `ApiResult<T>` (`Success` / `Error(code,…)` / `NetworkError`). Repositories sit on top and are the ViewModels' dependency surface. Some APIs exist for inactive surfaces that are not exposed in the current apps.

### Dependency injection (Koin)
`sharedModules()` (network + repositories) is combined with the player and Android modules at app startup. The Android apps override the in-memory `TokenManager`/`ServerRegistry` with persistent implementations. ViewModels are resolved with `koinViewModel()`; nav arguments flow in through Koin parameters / `SavedStateHandle`.

### Navigation & boot
Each app computes a start destination from registry/token/profile/offline state (`ServerSetup → Login → ProfileSelection → main`), then runs a Compose nav graph. The phone uses an Apple-aligned bottom shell (`Home`, `Libraries`, `For You`, `Calendar`, and conditional `Downloads`). The TV uses a tvOS-aligned top menu (`Home`, available media-type tabs, `Calendar`, plus search/profile actions). Deep links handle device pairing through `silo://device?...` and supported HTTPS `/device` or `/auth/device` URLs.

### Playback pipeline
The UI never owns the player directly — a `MediaController` drives the shared `SiloPlaybackService` (a Media3 `MediaSessionService`), so there is exactly one player and system session. For video, `PlaybackSessionManager` sends protocol-v3 capabilities and executes the server's direct/remux/transcode plan; classified failures and track/output changes use the v3 replan endpoint. `PlaybackSessionLifecycle` owns progress and outage handling. Offline playback bypasses the server through a local `file://` URI. The normative contract is in [`docs/playback`](docs/playback/README.md).

### Persistence
Per-profile/device player settings live in DataStore (debounced, flushed on `onStop`), tokens in `EncryptedSharedPreferences`, reader/audiobook local state in scoped stores or Room-backed projections, and downloaded bytes in public `MediaStore`/Downloads paths with original filenames and formats.

---

## Project structure

```
silo-android/
├── shared/            # KMP commonMain: network, repositories, models, ViewModels
├── android-shared/    # Media3 player + service, capability probes, downloads, settings stores, player DI
├── androidApp/        # Phone app (Jetpack Compose, Material 3)
├── androidTvApp/      # TV app (Compose for TV, D-pad)
├── docs/
│   ├── playback/      # Normative Media3-only playback architecture and validation
│   └── superpowers/   # Design specs + implementation plans (specs/, plans/)
├── scripts/           # Utility scripts, incl. FFmpeg AAR helpers
├── gradle/            # Version catalog (libs.versions.toml)
└── README.md / FEATURES.md
```

Tests live in each module's test source set (`commonTest`, `androidUnitTest`) using JUnit / `kotlin.test`, the Ktor `MockEngine` (shared API/repository tests), Robolectric (phone), and coroutines-test.

---

## Getting started

### Prerequisites
- **JDK 21**
- Android SDK with the configured compile SDK (36)
- A running **Silo server** for auth, browsing, and playback validation — see [`Silo-Server/silo-server`](https://github.com/Silo-Server/silo-server)

### Build

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidTvApp:assembleDebug
```

### Install on a connected device/emulator

```sh
./gradlew :androidApp:installDebug
./gradlew :androidTvApp:installDebug
```

On first launch, point the app at your Silo server URL, sign in, and pick a profile. (Android TV can't bootstrap first-time server setup — set the server up from the phone app or a web browser, then sign the TV in via username/password or QR/device pairing.)

---

## Testing

```sh
./gradlew test                          # all unit tests
./gradlew :shared:testDebugUnitTest     # shared KMP logic (ViewModels, repos, decoders)
./gradlew :android-shared:testDebugUnitTest
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidTvApp:testDebugUnitTest
```

---

## Conventions

- **Kotlin** with `gofmt`-equivalent cleanliness via the project's lint/format setup; keep packages lowercase and focused.
- **Shared first** — put platform-agnostic logic (models, networking, view-model logic, pure algorithms) in `shared`; keep Android-only concerns in `android-shared`; keep each app's module to its UI. New non-UI behavior that both apps need belongs in a shared module, not duplicated per app.
- **Compose** screens are thin; logic lives in ViewModels (testable in `commonTest` where possible).
- Design specs and implementation plans for larger efforts live under `docs/superpowers/{specs,plans}/`.
- This is part of a multi-repo Silo workspace — client-visible API/auth/playback changes often need coordinated work in `silo-server` (and the sibling `silo-apple` clients).

---

## Roadmap

Active design work lives in `docs/superpowers/specs/` with phased plans in `docs/superpowers/plans/`. Notable in-flight items:

- **Audiobook polish** — the phone and TV players have chapter-aware UI, speed, bookmarks, and sleep timers. Remaining work includes skip-silence, volume normalization, rich notification polish, Android Auto, and a phone widget.
- **Ebook reader enhancements** — real paginated EPUB (page turns), in-text search, highlights & notes (with a coordinated server change), font/brightness controls, and reading-time estimates across all server formats.
- **Picture-in-Picture** — not yet implemented on phone.
- **Admin management (users/sessions/logs/scans), Watch Together** — code/design work exists, but these are not currently exposed to users in the Android apps and need product/navigation decisions before being treated as live features.

Known gaps the docs track: TV has no reader/ebooks and no downloads management by design; Requests/Admin/Watch Together are not accessible on either Android surface today.

---

## Notes

- The Android phone and TV apps share one application ID, `org.siloserver.silo`, and publish as a single Google Play listing; Play delivers the right build per device via manifest feature filtering (phone requires a touchscreen, TV requires leanback).
- The Android modules target Java 21.
- The server repo lives at [`Silo-Server/silo-server`](https://github.com/Silo-Server/silo-server).

## License & Trademarks

Silo Android is licensed under `AGPL-3.0-or-later`. See [LICENSE](LICENSE).

The **Silo name, logo, and wordmark are trademarks of Silo Media L.L.C.** and
are **not** covered by the AGPL. You're free to fork and redistribute the code,
but forks and redistributions must not use the Silo brand as their identity and
must remove or replace the brand assets. Publishing a Silo-branded app to an app
store requires written permission. See [TRADEMARK.md](TRADEMARK.md) for what's
permitted — including referential use like "compatible with Silo."

The checked-in Media3 FFmpeg decoder AAR and other third-party dependencies retain their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
