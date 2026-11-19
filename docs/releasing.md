# Releasing

Releases run from GitHub Actions on `main`. Each run picks its own version and
build number from the repository's tags.

## Tester build

Actions → **Release** → Run workflow, leaving `bump` on `build`.

The run tags the commit `v1.0.0-beta.23`, uploads both apps to the Google Play
beta track as `1.0.0 (23)`, and publishes one APK per app on a GitHub
prerelease (`silo-android-1.0.0-23.apk`, `silo-android-tv-1.0.0-23.apk`). Its
notes list the pull requests merged since the previous build, grouped by label.

Play's "What's new" (500 characters at most) is Claude's short summary of those
pull request titles: what's new, and where to test. The run sends Claude only
the section names and titles, never pull request bodies, comments, or code. If
the summary fails, or the `ANTHROPIC_API_KEY` repository secret isn't set, the
run uses the plain list of Features and Fixes instead. Either way the run's
summary page shows the text.

It requires **Android Builds** to have passed on the commit, so run it once
`main` is green. It also runs every unit test on that commit before uploading.

The very first run has no tester build to continue from: enter `1.0.0` as
`version`.

If a run fails, use **Re-run failed jobs**: it keeps the same tag and build
number. Delete the tag instead only if that run never uploaded to Play. If Play
received the build, or you can't tell, keep the tag: a new run must not reuse a
build number Play has seen.
The next run refuses to start while the newest tag has no release, so its notes
don't skip changes no tester got.

## Store release

1. Actions → **Promote** → enter the newest tester build, such as
   `v1.0.0-beta.23` (Play's beta track only holds the newest one). Nothing is
   rebuilt: Play gets a production draft of that exact build, and GitHub gets a
   draft release `v1.0.0` with its APKs.
2. In Play Console → Production, review the draft release and send it for
   review.
3. On GitHub → Releases, edit the `v1.0.0` draft notes.
4. When Play approves, publish the GitHub release. The store Downloader codes
   and the `releases/latest` APK links then serve this version. After the first
   store release, switch the README, the user manual, and the community bot's
   answers from the beta codes to the store codes.

While the draft exists, version 1.0.0 counts as released: Release refuses
`bump: build`, and `bump: minor` starts 1.1.0. If Play rejects the build, delete
the GitHub draft and fix the problem. Then run Release with `version: 1.0.0`,
because once 1.1.0 has started, `bump: build` continues that one. Promote the
new build.

## After a store release

The next Release run needs `bump: minor` (the next version has new features)
or `bump: patch` (fixes only). `bump: build` refuses a version that's already
released.

## Numbers

- `1.0.0` is the version people see. It stays the same for every tester build
  until that version is released on Play.
- `23` is the build number: one counter for the whole repository that never
  resets. Settings → About shows `1.0.0 (23)`.
- Phone and TV ship together as one Play release. Play's versionCode comes from
  both numbers (`scripts/release-version.py`), so a higher version always
  wins, and within a version the later build wins.
- Tags: `vX.Y.Z-beta.B` for tester builds (GitHub prereleases) and `vX.Y.Z` for
  store releases. Older tags with build metadata, such as `v1.0.0+21`, were
  tester builds from before this scheme.

## Downloader and APK links

Until the first store release, give out the beta Downloader codes by AFTVnews,
2988950 (phones) and 6033910 (Android TV). The README, the user manual, and the
community bot's answers use them. They link to the newest tester build:
`releases/download/beta-latest/silo-android-latest-beta.apk` and
`releases/download/beta-latest/silo-android-tv-latest-beta.apk`. Every Release
run moves the `beta-latest` tag to its commit and replaces those two files on
the "Latest tester build" prerelease, unless a newer tester build already
exists.

The store Downloader codes, 1141108 (phones) and 2249835 (Android TV), link to
`releases/latest/download/silo-android-latest-universal-release.apk` and
`releases/latest/download/silo-android-tv-latest-universal-release.apk`. When a
store release is published, `published.yml` copies its APKs to those names.
Tester builds are prereleases, so they never move these links. Until the first
store release, `releases/latest` is the old `v1.0.0+21` tester build, so don't
give out the store codes yet. Someone moving from a beta build to a store code
has to uninstall first, because tester builds have higher version codes than the
store build.
