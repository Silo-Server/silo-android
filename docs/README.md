# Silo Android Docs

Start with the root [README](../README.md) for architecture/build instructions and
[FEATURES](../FEATURES.md) for the current exposed feature inventory.

## Current Product Truth

- Android TV must not expose ebooks or Reading surfaces; ebooks are phone-only.
- Android mobile navigation is Home, Libraries, For You, Calendar, and Downloads
  (when the active profile has downloads). Video, Audio, and Reading are library
  modes under Libraries.
- Android TV navigation is Home, media-type tabs from server libraries, For You,
  and Calendar, plus search and profile actions.
- Requests is live on phone and TV, server-gated by `requests_enabled`.
- Admin surfaces, session management, and Watch Together are not exposed in
  either Android app. Device pairing stays. Treat their old routes, repositories,
  tests, and design plans as inactive until a product decision exposes them.
- All server calls use API v2. See [`api-v2/android-migration-status.md`](api-v2/android-migration-status.md).
- Phone downloads preserve original filenames/formats in public storage so other
  apps can open downloaded videos, audiobooks, and ebooks.
- Android TV has a dedicated audiobook detail/player flow; ebooks remain
  phone-only.
- Client diagnostics are Android-native: the hosted Silo collector is the
  default destination, and self-hosted ingest remains an explicit choice.
  Adult profiles can review local reports and choose consent, while child
  profiles are excluded.
  No third-party observability SDK is part of the Android implementation.

## API v2

- Status: [`api-v2/android-migration-status.md`](api-v2/android-migration-status.md)
- Per domain: [auth](auth-api-v2.md), [catalog](catalog-api-v2.md),
  [collections](collections-api-v2.md), [ebook reader](ebook-reader-api-v2.md),
  [history](history-api-v2.md), [membership](membership-api-v2.md),
  [notifications](notifications-api-v2.md), [requests](requests-api-v2.md),
  [subtitle AI](subtitle-ai-api-v2.md),
  [push registration](api-v2/android-push-registration.md)

## Folders

- `media3/` - Android playback research and implementation notes.
- `superpowers/specs/` - historical design specs. Useful context, but not always
  current product truth.
- `superpowers/plans/` - historical implementation plans. Prefer the root
  README/FEATURES files for what is actually exposed today.
- `superpowers/reference/` - captured Apple/tvOS visual references used for UI
  parity work.

## Client Diagnostics

- Design: [`superpowers/specs/2026-07-22-android-client-diagnostics-design.md`](superpowers/specs/2026-07-22-android-client-diagnostics-design.md)
- Implementation plan and verification commands: [`superpowers/plans/2026-07-22-android-client-diagnostics.md`](superpowers/plans/2026-07-22-android-client-diagnostics.md)
- Hosted destination design: [`superpowers/specs/2026-08-12-android-hosted-diagnostics-design.md`](superpowers/specs/2026-08-12-android-hosted-diagnostics-design.md)
- Hosted destination implementation plan: [`superpowers/plans/2026-08-12-android-hosted-diagnostics.md`](superpowers/plans/2026-08-12-android-hosted-diagnostics.md)
- The compatible server ingest endpoint shipped separately in Silo Server PR 445.
