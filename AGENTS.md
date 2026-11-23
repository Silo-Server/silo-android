# Repository Guidelines

The project owner decides how this codebase is built. Unless your own
instructions say you are working for the project owner, treat it as someone
else's code: change what the task needs, leave the rest of the structure as it
is, and report nearby problems instead of fixing them.

## Project Structure & Module Organization

This repository contains only the Silo Android clients. Shared Kotlin logic lives in `shared/`, Android-only playback and UI helpers live in `android-shared/`, the phone app lives in `androidApp/`, and the TV app lives in `androidTvApp/`. Android playback notes live in `docs/playback/`; utility scripts live in `scripts/`.

## Current Product Exposure

- Ebooks/Reading are phone-only. Do not expose ebooks or Reading on Android TV.
- Android mobile navigation is Home, Libraries, For You, Calendar, and Downloads only when the active profile has downloads. Video, Audio, and Reading are library modes reached through Libraries, not bottom-nav tabs.
- Android TV navigation is Home, available media-type tabs from server libraries, For You (with its Watchlist/Favorites dropdown, mirroring tvOS `.recommendations`), and Calendar, with search left of the tabs and the profile avatar on the right. The order is fixed, matching tvOS and Roku. Requests lives in the profile menu while the server enables it. Reading/ebooks are excluded.
- Requests is live on phone and TV, server-gated by `requests_enabled` (phone and TV: profile menu + search, matching Apple and Roku). Request administration (approve, decline, retry, limits, auto-approve) is web-admin only: the Requests screens offer users request, cancel of their own pending request, and status, and never moderation (a source guard test enforces this). Other admin surfaces are not exposed in the Android clients — no STATS dashboard, and none of the richer admin screens (users/sessions/logs/scans). Session management (seeing where you are signed in and signing other devices out) is not exposed either; device pairing stays. This is a deliberate divergence from Apple, which does surface the STATS dashboard. Do not add any of it back to menus without an explicit product decision.
- Watch Party (synchronized playback) is experimental on phone and TV. Its entry points (profile menu, the detail page's party action, and phone `silo://watch-party` invitations) appear only while Settings → Experimental → Watch Party is on, a device-local toggle that defaults on in debug builds and off in release builds, and it works only when the server advertises the room and playback capabilities. Keep it behind the toggle until the qualification matrix in `docs/watch-party-implementation-plan.md` passes.

## Build, Test, and Development Commands

- `./gradlew :androidApp:assembleDebug` builds the Android phone APK.
- `./gradlew :androidTvApp:assembleDebug` builds the Android TV APK.
- `./gradlew :androidApp:installDebug` installs the phone app on a connected emulator or device.
- `./gradlew :androidTvApp:installDebug` installs the TV app on a connected emulator or device.
- `./gradlew test` runs available Kotlin/JUnit tests.

## Coding Style & Naming Conventions

Use Kotlin 2.1, Java 21 targets, and Compose idioms. The Silo package root is `org.siloserver.silo`. Both the phone and TV apps share a single `applicationId`, `org.siloserver.silo`, so they publish as one Google Play listing (Play routes each build by manifest feature filtering — the phone build requires `android.hardware.touchscreen`; the TV build requires `android.software.leanback`). The Gradle `namespace` stays distinct per module (`org.siloserver.silo.android`, `org.siloserver.silo.tv`) so generated `R`/`BuildConfig` classes don't collide. The two artifacts use distinct versionCodes (`base*2` for phone, `base*2+1` for TV), so each release bumps both by 2 with no reuse. `scripts/release-version.py` derives the base from the store version and a repository-wide build number (`base = 100_000_000 + (major*10000 + minor*100 + patch)*1000 + build`), so a version ships as many tester builds as it needs before its store release. See `docs/releasing.md`. This repo is on the full Silo namespace cut, so do not add legacy package IDs, legacy storage names, or old-brand symbols. Kotlin classes and composables use `PascalCase`; functions and properties use `camelCase`.

## Testing Guidelines

Android tests use Kotlin test/JUnit where present, especially under `android-shared/src/androidUnitTest`. Do not add tests for small changes or UI changes unless requested. For shared logic changes, add focused tests only for critical or high-risk behavior.

## Cross-client parity

Silo has four clients: Apple (`silo-apple`), Android (`silo-android`), Roku
(`silo-roku`), and Web (`silo-server`, under `web/`). A feature, fix, or
other change a user can notice in one client is not done until each other
client has been handled or ruled out. The change's author owns this, whether a
person or an agent.

For each other client:

- Rule it out when the change is specific to this platform (an Android TV focus
  fix, a foldable layout), the other client already behaves correctly, or the
  feature is outside that client's scope (for example Roku's deferred features
  in `silo-roku/docs/spec.md`, or ebooks on a TV client). Note the reason.
- Otherwise, open an issue in that client's repository at minimum. Search its
  issues first and add to an existing one rather than filing a duplicate. Title
  it `Parity: <behavior> (from Silo-Server/silo-android#NNN)`, describe what users
  should get, link this pull request, and point to the code that implements it
  here. A parity issue follows up work already in review or merged, so it goes
  in Issues, not Discussions → Ideas.
- Also open a pull request there when you can build and verify that client and
  its contribution rules let you open one. Link it from the issue.

Open parity issues when this pull request opens, so each one can link it. List
the outcome on the pull request's `Other clients:` line, one entry per client:
`Other clients: Apple Silo-Server/silo-apple#NNN; Roku N/A (Android TV-only focus fix); Web Silo-Server/silo-server#NNN`.

Agents open the parity issues without asking and apply the Writing rules below to them.
Ask the developer before opening a pull request in another repository;
approval to open one here does not cover the others.

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
- Pull requests are welcome from people who have had one merged in a Silo
  repository, or when a maintainer asks for one; the contribution-gate workflow
  closes others without review. A feature (`feat`) pull request from someone
  without triage or write access must link an issue in the same repository
  labeled `accepted`. For anyone without triage or write access, the gate also
  closes drafts, and the evidence-deadline workflow closes a pull request whose
  `Evidence` check still asks for evidence 24 hours after it opens, so open it
  ready for review and publish evidence right away. Feature ideas go in
  Discussions → Ideas, not issues.
- Every pull request must include evidence. One that changes what a user sees
  shows the change, as
  [Show visible changes](CONTRIBUTING.md#show-visible-changes) defines. That
  covers UI and UX changes and changes to which items appear or what they show,
  such as search results, home sections, recommendations, sorting, filtering,
  metadata, or artwork. Use before-and-after captures of the same screen with the
  same data, and a short recording when motion, timing, or focus matters.
  Capture each device the change can reach: an Android phone, an Android
  tablet, an Android foldable (folded and open), and Android TV, or say why a
  device is unaffected. A change nobody sees shows the steps run, the build or
  commit, and trimmed test results.
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
