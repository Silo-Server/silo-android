# Silo Android — Feature List

A detailed inventory of what the Android **phone** and **TV** clients do today. Legend:

- ✅ implemented
- 🟡 partial / basic ("bones-level") — works but slated for improvement
- 🚧 planned (design/plan exists, not built)
- ➖ not present on this platform (by design or not yet built)

File pointers are repository-relative.

> **Important exposure note:** Requests is live on both Android surfaces, gated by the server's `requests_enabled` flag (`/api/v1/requests/status`), and reached from the profile menu and search — matching the Apple clients. The admin stats dashboard is live for acting admins via Settings. The richer admin screens (users/sessions/logs/scans) and Watch Together remain inaccessible.

---

## Playback

| Feature | Phone | TV | Notes |
|---|:---:|:---:|---|
| Media3/ExoPlayer engine via shared `MediaSessionService` | ✅ | ✅ | One session per process; UI drives it via `MediaController`. `android-shared/.../player/SiloPlaybackService.kt` |
| Direct Play | ✅ | ✅ | Progressive HTTP; server-selected from advertised capabilities |
| Remux (HLS or progressive container/audio re-mux) | ✅ | ✅ | Server-selected protocol-v3 plan |
| Transcode (HLS full re-encode) | ✅ | ✅ | Server-selected plan or classified v3 replan |
| Runtime recovery (undecodable track/stall/error → replan) | ✅ | ✅ | `PlaybackPreflightListener` + `PlaybackSessionManager.replanActiveVideoSession` |
| Mid-stream audio/subtitle-track switch | ✅ | ✅ | Protocol-v3 replan; may change delivery |
| Hardware decoder enumeration (H.264/HEVC/AV1/VP9/DV) | ✅ | ✅ | `MediaCodecCapabilitiesProbe` |
| Dolby Vision profiles 5 / 7 / 8 | 🟡 | 🟡 | Advertised only from codec + display probes; P7 additionally requires concurrent DV and HEVC decoder instances. 4K DV output still requires fixture validation on a DV display. |
| Panel HDR probe (HDR10, HDR10+, HLG, DV) + per-profile HDR toggle | ✅ | ✅ | `DisplayHdrProbe` |
| Audio passthrough (E-AC3 JOC/Atmos, TrueHD, DTS-HD) | ✅ | ✅ | TV prioritizes passthrough; `AudioCapabilityManager` |
| FFmpeg audio extension (lossless fallback) | ✅ | ✅ | Media3 1.11.0-aligned, build-flag gated; `FfmpegAudioSupport` |
| Staged playback buffer | ✅ | ✅ | `PlaybackBufferPolicy` currently defaults to Smooth Playback |
| Refresh-rate matching | ✅ | ➖ | Phone display mode; TV defers to HDMI sink |
| HDMI EDID-driven display mode | ➖ | ✅ | `HdrDisplayController` |
| Subtitle selection + styling (font/bg/position) | ✅ | ✅ | Media3 `SubtitleManager`; server plans render, convert, or burn-in fidelity |
| Subtitle sync offset (±10s) / audio sync (±5s) | ✅ | ✅ | Per-profile |
| Subtitle provider search + download | ✅ | ✅ | |
| AI subtitle transcription / translation (quota-tracked) | ✅ | ✅ | TV: `TvAiTranslateDialog` |
| AI description translation (on-view, server-gated) | ✅ | ✅ | `DescriptionTranslationController`; gated by `/api/v1/metadata/ai/status`; metadata-language setting in Settings |
| Intro auto-skip (+ manual skip banner) | ✅ | ✅ | |
| Chapters | ✅ | ✅ | Server-extracted; TV scrubber markers |
| Sleep timer | ✅ | ✅ | Configurable default |
| Playback speed | ✅ | ✅ | |
| Video gravity (fit / fill / stretch) | ✅ | ✅ | |
| Lock-screen / notification / headset / Assistant controls | ✅ | ✅ | Via `MediaSession` |
| D-pad transport, info HUD, chapter scrubber | ➖ | ✅ | `TvPlayerHud`, `TvPlayerScrubber` |
| Landscape-on-play (auto-rotate aware) | 🚧 | ➖ | Implemented then reverted; pending re-apply |
| Picture-in-Picture | ✅ | ✅ | `SiloPictureInPictureCoordinator`; enters on home-press during playback |

## Watch Together (not exposed)

| Feature | Phone | TV | Notes |
|---|:---:|:---:|---|
| Create / join / leave room | 🚧 | 🚧 | Code/design artifacts exist, but users cannot access this flow |
| Clock sync (NTP-style) + drift correction | 🚧 | 🚧 | Shared infrastructure exists but is not a live feature |
| Host vs guest transport gating | 🚧 | 🚧 | Not reachable from production navigation |
| Room snapshots / member list / suggestions / voting | 🚧 | 🚧 | Not reachable from production navigation |
| Graceful reconnect + host-closed auto-exit | 🚧 | 🚧 | Not reachable from production navigation |

## Offline & Downloads

| Feature | Phone | TV | Notes |
|---|:---:|:---:|---|
| Download video, audiobooks, and books (WorkManager) | ✅ | ➖ | TV is streaming-only |
| Public original-format files | ✅ | ➖ | Original filenames/extensions are preserved so other apps can discover/open them |
| Scoped `MediaStore` storage (API 30+) + Room metadata | ✅ | ➖ | `DownloadStorage`, `DownloadMetadataStore` |
| Offline-first playback (local `file://`, no session) | ✅ | ➖ | `OfflineMediaResolver` |
| Open downloads in other apps | ✅ | ➖ | Video/audio/books expose MIME-aware external-open intents |
| Downloads manager UI (per-item / per-section delete, storage usage) | ✅ | ➖ | `DownloadsScreen` |
| Boot directly to Downloads when launched offline | ✅ | ➖ | |

## Library, browse & discovery

| Feature | Phone | TV | Notes |
|---|:---:|:---:|---|
| Media modes from server libraries | ✅ | ✅ | Phone reaches Video/Audio/Reading through Libraries; TV exposes Movies/TV/Music/Audiobooks as top tabs and excludes Reading |
| Home: Continue Watching, Recently Added/Released, recommendations | ✅ | ✅ | Server-defined sections |
| Library browse: genre/rating filters, sort, infinite grid | ✅ | ✅ | |
| Collections (global + library-scoped) | ✅ | ✅ | |
| Item detail: movies, series → seasons → episodes, multi-version, cast/crew | ✅ | ✅ | |
| Person detail from cast/crew | ✅ | ✅ | Long IDs supported; filmography rows filter per platform |
| Search (scoped by media type, debounced, paginated) | ✅ | ✅ | |
| Release calendar | ✅ | ✅ | Top-level mobile tab and TV top-menu tab |
| Live home refresh (events websocket) | ✅ | ✅ | `HomeRealtimeCoordinator`: user_state/catalog channels, 2s debounce; TV also refreshes on resume |
| System "Watch Next" row integration | ➖ | ✅ | `WatchNextRepository` (tvprovider) |
| Requests | ✅ | ✅ | Server-gated by `requests_enabled`; profile menu + search entry points |

## Reading (ebooks)

| Feature | Phone | TV | Notes |
|---|:---:|:---:|---|
| EPUB reader | 🟡 | ➖ | Reflowable reader, still improving pagination |
| PDF reader | ✅ | ➖ | `PdfRenderer` |
| CBZ (comic) reader | ✅ | ➖ | |
| CBR reader | ➖ | ➖ | Original can be downloaded/opened externally; no in-app RAR renderer |
| TXT / Markdown reader | ✅ | ➖ | |
| FB2 / FBZ (FictionBook) reader | ✅ | ➖ | |
| MOBI / AZW / AZW3 | 🟡 | ➖ | In-app when the server converts to EPUB; otherwise original opens externally |
| Themes (light/dark/sepia), text size, margins | ✅ | ➖ | |
| Table of contents / sections | ✅ | ➖ | EPUB |
| Bookmarks (local + server sync) | ✅ | ➖ | |
| Reading progress tracking + server sync | ✅ | ➖ | |
| In-text search | 🚧 | ➖ | Planned |
| Highlights & notes | 🚧 | ➖ | Server model exists; UI planned (+ server generalization) |
| Font family / brightness controls | 🚧 | ➖ | Planned |

## Audio (audiobooks)

| Feature | Phone | TV | Notes |
|---|:---:|:---:|---|
| Audiobook detail surface | ✅ | ✅ | TV has a dedicated tvOS-style audiobook hero/detail flow |
| Audiobook player (cover, metadata, chapters) | ✅ | ✅ | Shares the Media3 engine and shared audiobook ViewModel |
| Resume + periodic progress save | ✅ | ✅ | Progress flows through the shared user-state path |
| Playback speed | ✅ | ✅ | TV includes speed panel and default speed support |
| Sleep timer (minutes, end-of-chapter, end-of-book) | ✅ | ✅ | |
| Bookmarks | 🟡 | 🟡 | Local-only (no server endpoint yet) |
| Direct-play of cover-art audiobooks (no needless transcode) | ✅ | ✅ | Advertises still-image codecs |
| Chapter navigation (prev/next), current-chapter UI | ✅ | ✅ | |
| Skip-silence / volume boost, Android Auto, widget | 🚧 | ➖ | Planned |

## Profiles, personalization & engagement

| Feature | Phone | TV | Notes |
|---|:---:|:---:|---|
| Household profiles (multiple per account) | ✅ | ✅ | `ProfileRepository` |
| PIN-protected & child profiles, content-rating limits | ✅ | ✅ | |
| Per-profile language / subtitle / playback prefs | ✅ | ✅ | |
| Library access restrictions per profile | ✅ | ✅ | |
| Favorites & watchlist | ✅ | ✅ | TV: from Settings |
| Ratings | ✅ | ✅ | |
| Watch history | ✅ | ✅ | |
| Content requests (browse/search TMDB, status tracking) | 🚧 | 🚧 | Not currently accessible in either Android app |
| Release calendar | ✅ | ✅ | |
| Notifications inbox (paginated, realtime updates, mark-read) | ✅ | ✅ | REST + WebSocket |

## Servers, accounts & admin

| Feature | Phone | TV | Notes |
|---|:---:|:---:|---|
| Multiple Silo servers + switching (encrypted per-server tokens) | ✅ | ✅ | `ServerRegistry` |
| First-time server setup (admin creation) | ✅ | ➖ | TV signs in to already-set-up servers |
| Username/password login | ✅ | ✅ | |
| QR / device pairing sign-in | 🟡 | ✅ | TV displays code; phone approves device logins |
| Single-flight token refresh (REST + media streams) | ✅ | ✅ | Auth plugin + `MediaAuthInterceptor` |
| Settings (account, appearance, playback, subtitles, notifications) | ✅ | ✅ | Effective-settings cascade synced to server |
| Admin screens | 🚧 | 🚧 | Not currently accessible in either Android app |
| Admin gate (account admin **and** primary profile) | 🚧 | 🚧 | Gate logic exists, but no exposed app entry point |

---

## Platform summary

**Phone** is the full-featured client for playback, downloads/offline, the ebook reader, the audiobook player, the release calendar, profiles, search, and library browsing.

**TV** is a 10-foot, D-pad client focused on browsing and playback, including audiobooks, calendar, the subtitle suite, person detail, and system Watch Next integration. It intentionally omits ebooks/reading and downloads management.

**Not currently exposed on either Android surface:** full admin management (users/sessions/logs/scans) and Watch Together. The admin **stats dashboard** is exposed (Settings → Admin, acting admins only).

Both apps share the same networking, auth, repositories, most ViewModels, and the entire Media3 playback/capability stack.
