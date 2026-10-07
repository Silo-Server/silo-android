# Contributing to Silo Android

The [Silo contribution guide](https://github.com/Silo-Server/.github/blob/main/CONTRIBUTING.md)
covers project-wide coordination, focused changes, evidence, AI disclosure, and
pull request expectations. Those requirements apply here; this guide adds the
Android-specific workflow.

## Before you start

Open an [issue](https://github.com/Silo-Server/silo-android/issues) before
implementing a feature, navigation or behavior change, large refactor, or work
that changes the shared server contract. Documentation, narrow fixes, and
well-scoped parity corrections can go straight to a pull request.

This repository owns the Android phone and Android TV clients. Server/API work
belongs in [`silo-server`](https://github.com/Silo-Server/silo-server), and
shared client behavior should be checked against
[`silo-apple`](https://github.com/Silo-Server/silo-apple).

## Development setup

Read [README.md](README.md) for prerequisites and build commands, then read
[AGENTS.md](AGENTS.md) for the current product exposure, module ownership, and
testing guidance.

Build the two debug applications with JDK 21 and an Android SDK:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidTvApp:assembleDebug
```

A running Silo server is required for realistic authentication, browsing, and
playback validation. Do not commit SDK overrides, signing material, generated
build output, logs, or media fixtures.

## Validate your change

Run focused module tests while iterating. Before opening a pull request, run the
same checks as CI:

```sh
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
./gradlew testDebugUnitTest
./gradlew \
  :android-shared:lintDebug :androidApp:lintDebug :androidTvApp:lintDebug \
  :androidApp:lintVitalRelease :androidTvApp:lintVitalRelease
```

Then build every affected app with `:androidApp:assembleDebug` and/or
`:androidTvApp:assembleDebug`. Do not regenerate a lint baseline to hide a new
finding. Exercise visible changes on each affected phone or TV surface.

## Show visible changes

A pull request that changes what a user sees must show the change in its
Evidence section, so reviewers can see it without building the branch. In this
repository that means Android phone and Android TV. A change is visible when it
alters any of these:

- layout, styling, copy, navigation, focus, empty and error states;
- which items a screen shows, or in what order: search results, home sections,
  recommendations, library browsing, collections, sorting, or filtering;
- what an item shows: titles, artwork, descriptions, ratings, badges, episode
  grouping, or availability;
- playback behavior a user notices, such as default audio or subtitle tracks,
  markers, controls, or resume position.

Provide evidence that fits the change:

- **Changes to a screen:** before-and-after screenshots of the same screen with
  the same data, one pair per affected surface. Add a short recording when
  motion, timing, focus movement, or a multi-step flow matters.
- Name the surface and the build or commit each capture came from.

Capture against a test library or public-domain media where you can, and keep
passwords, tokens, and API keys out of every capture. Then put the evidence in
one of two places:

- **On GitHub:** attach the screenshots or recordings under the pull request's
  Evidence heading. Everything on GitHub is public, so crop or blur hostnames,
  URLs, account names, and personal library contents.
- **On [evidence.siloserver.org](https://evidence.siloserver.org/) (optional):**
  only you and Silo maintainers can open what you publish there, after signing
  in with GitHub, so captures need no cropping or blurring. Captions that start
  with `Before:` and `After:` become a side-by-side comparison, and recordings
  get a player. Upload from the Details link of the pull request's `Evidence`
  check, or with the command line (Node.js 22 or later):
  `npx @silo-server/evidence login` once on each computer, then
  `npx @silo-server/evidence publish <folder> --pr <number>`. The
  [package README](https://www.npmjs.com/package/@silo-server/evidence) describes
  the folder. Then write
  `Evidence: https://evidence.siloserver.org/r/silo-android/pr-<number>/` under
  the Evidence heading. Until a pull request of yours has merged here, a
  maintainer approves you once before your first upload.

The pull request's `Evidence` check passes once evidence is published or
attached. It asks for evidence when the change touches the phone app, the TV app, or the Android code they share,
or when a maintainer adds the `evidence-required` label; a maintainer adds
`evidence-not-needed` when nothing visible changed. Changes users cannot see
write `Evidence: none, no user-visible change`. If you could not capture
evidence, say why; the reviewer decides whether the pull request can merge
without it.

## Open the pull request

Use a Conventional Commit title, fill in the pull request template, explain
which surfaces are affected, paste the actual validation results, and call out
any server or Apple coordination. Read
the [AI-assisted contribution policy](https://github.com/Silo-Server/silo-server/blob/main/docs/ai-contributions.md)
and include its disclosure block.

## Instructions for coding agents

Coding agents must read [AGENTS.md](AGENTS.md) before changing the repository
(`CLAUDE.md` points to the same guidance). The organization-wide contribution
guide and AI-assisted contribution policy apply to agent and human authors
equally.
