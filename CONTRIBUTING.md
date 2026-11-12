# Contributing to Silo Android

> [!IMPORTANT]
> The most helpful way to contribute to Silo right now is a clear, accurate
> issue. Feature ideas go in
> [Discussions → Ideas](https://github.com/Silo-Server/silo-android/discussions/categories/ideas),
> not issues. Pull requests from outside the team are for bug fixes; anything
> else needs a maintainer to ask for it. Every pull request needs evidence.
> Pull requests that break these rules, drafts, and pull requests still without
> evidence a day after they open are closed automatically without review.

## Why we're asking for issues

Pull requests now arrive faster than we can review them carefully. Reviewing a
change properly means reading every line, checking it against work already in
flight, re-running validation, and owning the result after it merges. Writing
the change ourselves from a precise issue takes less time, and it keeps every
change on one workflow with the same tests and the same review.

This is about review capacity, not the quality of anyone's work, and it applies
whether or not you used AI. We'll revisit it as Silo approaches 1.0. Thank you
for taking the time to write things up well.

## Write a useful issue

Open an [issue](https://github.com/Silo-Server/silo-android/issues) for problems
in the Android phone and TV apps. Server and API problems belong in
[`silo-server`](https://github.com/Silo-Server/silo-server/issues/new/choose).
Search first; if an issue already covers the problem, add what's new there.

Using Claude Code or Codex? The
[Silo troubleshooting skill](https://github.com/Silo-Server/silo-troubleshooting-skill)
helps you collect these details and drafts the issue, then has independent
reviewers check it against your evidence before you post it.

- One problem per issue.
- Describe what you observed before any theory about the cause.
- Give exact steps to reproduce, expected and actual behavior, the app version,
  your device (phone or TV) and Android version, and the Silo server version.
- Paste raw logs rather than a summary. Redact credentials, tokens, personal
  data, and private media details, mark each redaction, and leave the rest
  untouched.
- If you found the cause or have a fix in mind, add it under a Technical notes
  heading, apart from what you observed. Point to the files involved; a short
  code excerpt is fine. We may implement it differently.
- Disclose AI use, as described in the
  [Silo contribution guide](https://github.com/Silo-Server/.github/blob/main/CONTRIBUTING.md#ai-assisted-contributions).

Report security vulnerabilities privately with **Report a vulnerability** on
the repository's Security tab, not in a public issue.

## Suggest a feature

Post feature ideas for the Android phone and TV apps in
[Discussions → Ideas](https://github.com/Silo-Server/silo-android/discussions/categories/ideas),
not as issues. Search first and upvote an existing idea rather than repeating
it. Describe the problem the feature solves and who it affects. Ideas that need
new server behavior belong in
[silo-server's Ideas](https://github.com/Silo-Server/silo-server/discussions/categories/ideas).
Maintainers open an issue for an idea when they plan to build it or want help
building it.

## Pull requests

These rules apply to every pull request from someone without triage or write
access. A pull request that follows them can still be closed; we don't owe a
review or a merge.

- **Fix a bug.** Give the pull request a `fix` or `perf` title. A crash, a
  regression, wrong behavior, and a real performance problem are bugs; a change
  to how Silo behaves is a feature, even when the pull request calls it a fix.
  Anything else, including features, refactors, and test or documentation
  changes, needs a maintainer to ask for it by labeling an issue in this
  repository `accepted`; link that issue in the description. A pull request of
  yours that merged before doesn't change this.
- **Open it ready for review.** Drafts are closed, both when they open and when
  they are converted to drafts.
- **Keep three or fewer open.** A fourth open pull request of yours in the same
  repository is closed when it opens. Get one merged or closed first.
- **Show evidence.** Every pull request needs evidence, as
  [Show visible changes](#show-visible-changes) describes. A pull request whose `Evidence` check still asks for
  evidence 24 hours after it opens, or after a maintainer reopens it, is closed
  without review. Publish or attach evidence soon after you open it.

A workflow applies these rules when a pull request opens and again when its
title or description changes. Closing isn't a judgment of the work. If the problem still matters, open an issue for it, or
post a feature idea in Ideas, and link the closed pull request; we may use the
code as a reference. A maintainer who wants a closed pull request reopens it,
and the workflow leaves it alone from then on, apart from the evidence deadline.

The [Silo contribution guide](https://github.com/Silo-Server/.github/blob/main/CONTRIBUTING.md)
covers project-wide coordination, focused changes, evidence, AI disclosure, and
pull request expectations. Those requirements apply here; this guide adds the
Android-specific workflow.

### Help us close things

Finding what can be closed helps more than another pull request:

- **Duplicates:** comment on the newer issue or pull request with a link to the
  original.
- **Fixed bugs:** if an open issue no longer happens on the latest build, say so
  and give the build.
- **Dead pull requests:** if a pull request was superseded or abandoned, link
  what replaced it.

If an open pull request already covers your bug, test it and comment with what
you saw instead of opening another.

## Before you start

Anything but a bug fix needs an issue a maintainer has labeled `accepted`
before you implement it; start with a post in Ideas. Open an
[issue](https://github.com/Silo-Server/silo-android/issues) before implementing
a navigation or behavior change, large refactor, or work that changes the shared
server contract. Documentation, narrow fixes, and well-scoped parity
corrections can go straight to a pull request.

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

Every pull request shows evidence that the change works, so reviewers can see it
without building the branch. A change users can see shows the change itself. A
change users can't see shows how you checked it: the steps you ran, the build or
commit, and trimmed test results.

A pull request that changes what a user sees must show the change in its
Evidence section. In this repository that means Android phone and Android TV. A
change is visible when it alters any of these:

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
- **Every affected device:** the phone app lays out screens differently on a
  phone, a tablet, and a foldable, and the TV app has its own interface. For a
  phone app change, capture an Android phone, an Android tablet, and an Android
  foldable such as a Galaxy Z Fold or Pixel Fold, both folded and open. Add
  Android TV when the change reaches the TV app, which shared code under
  `android-shared/` usually does. Emulator captures are fine. To skip a
  device, say why the change cannot reach it.
- Name the device, the build or commit, and for a foldable whether it was
  folded or open, for each capture.
- **Changes nothing shows**, such as internal refactors, CI, or performance
  work: the steps you ran, the build or commit, and trimmed test or benchmark
  results that show the change does what the description says.

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
  maintainer approves you once before your first upload. If that approval
  hasn't come through, attach the evidence on GitHub instead.

The pull request's `Evidence` check asks for evidence on every pull request and
passes once evidence is published or attached. A maintainer can waive it with
the `evidence-not-needed` label, for example for an automated dependency
update. A pull request from someone without triage or write access whose check
still asks for evidence 24 hours after it opens is closed without review; a
maintainer can reopen it once evidence is up. If you could not capture part of
the evidence, say why; the reviewer decides whether the pull request can merge
without it.

## Open the pull request

Use a Conventional Commit title, fill in the pull request template, explain
which surfaces are affected, paste the actual validation results, and call out
any server or Apple coordination. Read
the [AI-assisted contribution policy](https://github.com/Silo-Server/silo-server/blob/main/docs/ai-contributions.md)
and include its disclosure block.

## Licensing of contributions

Silo Android is licensed under `AGPL-3.0-or-later` with the platform library
additional permission in [PLATFORM-EXCEPTION.md](PLATFORM-EXCEPTION.md).

By submitting a contribution, you agree that it is licensed under those
same terms, the AGPL *including* the additional permission. If you cannot
agree to the additional permission, say so in your pull request before it
is merged.

You retain copyright in your contribution; no assignment is requested.

## Instructions for coding agents

Coding agents must read [AGENTS.md](AGENTS.md) before changing the repository
(`CLAUDE.md` points to the same guidance). The organization-wide contribution
guide and AI-assisted contribution policy apply to agent and human authors
equally.
