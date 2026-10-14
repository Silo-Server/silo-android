# Releasing

Releases run from GitHub Actions on `main`. Each run picks its own version and
build number from the repository's tags.

## Tester build

Actions → **Release** → Run workflow, leaving `bump` on `build`.

The run tags the commit `v1.0.0-beta.23`, uploads both apps to the Google Play
beta track as `1.0.0 (23)`, and publishes one APK per app on a GitHub
prerelease (`silo-android-1.0.0-23.apk`, `silo-android-tv-1.0.0-23.apk`). Its
notes list the pull requests merged since the previous build, grouped by label.
The same list, shortened, becomes Play's "What's new".

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
4. When Play approves, publish the GitHub release. The Downloader codes and the
   `releases/latest` APK links then serve this version.

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

Downloader by AFTVnews codes 1051382 (phones) and 1636227 (Android TV), the
README, and the user manual link to
`releases/latest/download/silo-android-latest-universal-release.apk` and
`releases/latest/download/silo-android-tv-latest-universal-release.apk`. When a
store release is published, `published.yml` copies its APKs to those names.
Tester builds are prereleases, so they never move these links.
