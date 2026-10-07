# Repository Guidelines

## Project Structure & Module Organization

This repository contains only the Silo Android clients. Shared Kotlin logic lives in `shared/`, Android-only playback and UI helpers live in `android-shared/`, the phone app lives in `androidApp/`, and the TV app lives in `androidTvApp/`. Android playback notes live in `docs/playback/`; utility scripts live in `scripts/`.

## Current Product Exposure

- Ebooks/Reading are phone-only. Do not expose ebooks or Reading on Android TV.
- Android mobile navigation is Home, Libraries, For You, Calendar, and Downloads only when the active profile has downloads. Video, Audio, and Reading are library modes reached through Libraries, not bottom-nav tabs.
- Android TV navigation is Home, available media-type tabs from server libraries, For You (with its Watchlist/Favorites dropdown, mirroring tvOS `.recommendations`), Calendar, and Requests while the server enables it (mirroring tvOS `.requests`), plus search and profile actions. Reading/ebooks are excluded.
- Requests is live on phone and TV, server-gated by `requests_enabled` (phone: profile menu + search; TV: a top-bar tab + search, matching Apple). Admins who can moderate requests approve, decline, and retry them in the Requests screens, as on Apple; limits and auto-approval stay in web admin. Other admin surfaces are not exposed in the Android clients — no STATS dashboard, and none of the richer admin screens (users/sessions/logs/scans). Session management (seeing where you are signed in and signing other devices out) is not exposed either; device pairing stays. This is a deliberate divergence from Apple, which does surface the STATS dashboard. Do not add any of it back to menus without an explicit product decision.
- Watch Party (synchronized playback) is experimental on phone and TV. Its entry points (profile menu, the detail page's party action, and phone `silo://watch-party` invitations) appear only while Settings → Experimental → Watch Party is on, a device-local toggle that defaults on in debug builds and off in release builds, and it works only when the server advertises the room and playback capabilities. Keep it behind the toggle until the qualification matrix in `docs/watch-party-implementation-plan.md` passes.

## Build, Test, and Development Commands

- `./gradlew :androidApp:assembleDebug` builds the Android phone APK.
- `./gradlew :androidTvApp:assembleDebug` builds the Android TV APK.
- `./gradlew :androidApp:installDebug` installs the phone app on a connected emulator or device.
- `./gradlew :androidTvApp:installDebug` installs the TV app on a connected emulator or device.
- `./gradlew test` runs available Kotlin/JUnit tests.

## Coding Style & Naming Conventions

Use Kotlin 2.1, Java 21 targets, and Compose idioms. The Silo package root is `org.siloserver.silo`. Both the phone and TV apps share a single `applicationId`, `org.siloserver.silo`, so they publish as one Google Play listing (Play routes each build by manifest feature filtering — the phone build requires `android.hardware.touchscreen`; the TV build requires `android.software.leanback`). The Gradle `namespace` stays distinct per module (`org.siloserver.silo.android`, `org.siloserver.silo.tv`) so generated `R`/`BuildConfig` classes don't collide. The two artifacts use distinct versionCodes (`base*2` for phone, `base*2+1` for TV), so each release bumps both by 2 with no reuse. Releases derive the base from the marketing version plus a build number (`base = 100_000_000 + (major*10000 + minor*100 + patch)*1000 + build`), so the same version can ship repeatedly as build 1, 2, 3 the way TestFlight does — bump the build number rather than inventing a new patch version for a re-release. This repo is on the full Silo namespace cut, so do not add legacy package IDs, legacy storage names, or old-brand symbols. Kotlin classes and composables use `PascalCase`; functions and properties use `camelCase`.

## Testing Guidelines

Android tests use Kotlin test/JUnit where present, especially under `android-shared/src/androidUnitTest`. Do not add tests for small changes or UI changes unless requested. For shared logic changes, add focused tests only for critical or high-risk behavior.

## Writing

Run a final readability pass on every human-facing issue, pull request,
document, or status update.

- Lead with the outcome.
- Use concrete, plain language and active voice.
- Cut filler, stock framing, repetition, and promotional claims.
- Preserve meaning, evidence, citations, uncertainty, and established
  terminology.
- Never rewrite exact quotations, commands, logs, identifiers, API names, or
  contractual language.
- Match the tone to the audience and use only formatting that improves
  readability.

## Pull requests

Never create a pull request unless the developer explicitly asks for one.

Use a Conventional Commit title in plain language. Start the body with the
problem, explain the solution next, and end with the required AI disclosure,
including the exact model identifier, agent harness, and any other AI tooling.
Include repository-required issue links, validation evidence, risks, and
follow-up work.

- Keep one concern per pull request. If an honest description needs the word
  "also," split the work.
- Every pull request that changes what a user sees must include evidence, as
  [Show visible changes](CONTRIBUTING.md#show-visible-changes) defines. That
  covers UI and UX changes and changes to which items appear or what they show,
  such as search results, home sections, recommendations, sorting, filtering,
  metadata, or artwork. Use before-and-after captures of the same screen with the
  same data, and a short recording when motion, timing, or focus matters. Write
  `Evidence: none, no user-visible change` only when that is true.
- Attach evidence on GitHub under the PR body's Evidence heading, or publish it
  with `npx @silo-server/evidence publish <folder> --pr <number>` and put its
  `Evidence:` link there. Only Silo maintainers and the pull request's author can
  open a published page. GitHub has no API for attaching images to a pull
  request, so an agent either publishes with the CLI or gives the developer the
  captures to attach. Check media for private information before it goes on
  GitHub. When publishing exits 4, ask the developer to run
  `npx @silo-server/evidence login`; never approve that login or read the saved
  key. Never commit PR-only assets such as `.github/pr-assets/`.
- When babysitting a pull request, poll checks and review comments created
  after the last push. Verify bot findings against the source, fix real issues,
  and dismiss false positives with a written reason. Remain quiet when nothing
  new has appeared. Stop when the latest commit is green.

## Security & Configuration Tips

Do not commit local SDK overrides, signing material, logs, tool state, generated build output, or media fixtures. A running Silo server is required for realistic auth, browsing, and playback validation.
