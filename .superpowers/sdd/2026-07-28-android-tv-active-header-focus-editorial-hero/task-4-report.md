# Task 4 Report: Android TV Focus and Phone/TV Hero Verification

Date: 2026-07-28
Worktree: `fix/tv-for-you-cold-navigation worktree`
Final local HEAD: `9c251293dc321385ea9be205a0732cc7b14b1251`

## Summary

Task 4 verification found one Important review issue in the previously approved Task 1 focus path. I fixed it locally in `9c251293` by preventing non-Search secondary routes from falling through to Home focus when `selectedRoot == null`. The repeat independent review approved the updated diff.

Automated tests, supply-chain checks, release builds, APK signing verification, and artifact packaging completed. Dedicated emulator smoke did not produce a fully clean pass, so I did not push or update PR #126.

## Local Commits Added

- `9c251293 fix(tv): avoid home focus fallback on secondary routes`

## Verification Commands

Focused TV regressions:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests "org.siloserver.silo.tv.ui.shell.TvTopMenuFocusRequestTest" \
  --tests "org.siloserver.silo.tv.ui.shell.TvShellFocusStateTest" \
  --tests "org.siloserver.silo.tv.ui.components.TvSkylineUpNavigationTest" \
  --tests "org.siloserver.silo.tv.ui.components.TvFocusMarqueeModelTest" \
  --rerun-tasks --max-workers=2 --no-daemon
```

Result: `BUILD SUCCESSFUL`, 68 tasks executed.

Focused phone regression:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests "org.siloserver.silo.android.ui.screens.home.FeaturedHeroMetadataTest" \
  --rerun-tasks --max-workers=2 --no-daemon
```

Result: `BUILD SUCCESSFUL`, 80 tasks executed.

Supply-chain checks:

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Result: both exited 0. Self-test output: `All supply-chain policy self-tests passed`.

Full release gate:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --rerun-tasks --max-workers=2 --no-daemon
```

Result: `BUILD SUCCESSFUL in 6m 29s`, 313 tasks executed.

The test XML directory immediately after the full release gate only contained focused suites, so I reran the unfiltered unit-test tasks explicitly:

```bash
./gradlew :androidApp:testDebugUnitTest :androidTvApp:testDebugUnitTest \
  --rerun-tasks --max-workers=2 --no-daemon
```

Result: `BUILD SUCCESSFUL in 1m 20s`, 104 tasks executed.

XML totals after explicit unfiltered rerun:

- `androidApp/build/test-results/testDebugUnitTest`: 85 suites, 477 tests, 0 skipped, 0 failures, 0 errors.
- `androidTvApp/build/test-results/testDebugUnitTest`: 88 suites, 646 tests, 0 skipped, 0 failures, 0 errors.

Diff hygiene:

```bash
git diff --check origin/main...HEAD
git status --short --branch
git log --oneline origin/main..HEAD
```

Result before writing this report: no whitespace errors; branch ahead of `origin/fix/tv-for-you-cold-navigation`.

## Independent Review

Initial review finding:

- Important: non-Search secondary routes could call `requestMenuFocus(null)` and fall through to Home in `TvTopMenuBar`.

Fix:

- Added `TvShellFocusState.requestMenuFocusIfAvailable`.
- Updated content-Up routing in `TvMainShell` to allow null targeting only for Search.
- Added regression coverage in `TvShellFocusStateTest`.

Repeat review result:

- No blocking findings. Approved.
- Residual risk noted by reviewer: the content-Up fix is covered by state/helper unit tests, not a full Compose focus integration test.

## Emulator Smoke

Physical device safety:

- ADB showed an unapproved physical TV device; I did not target it.
- TV smoke used the dedicated TV emulator.
- Phone smoke used the dedicated phone emulator.

TV smoke on final TV APK:

- Installed `androidTvApp/build/outputs/apk/release/androidTvApp-universal-release.apk`
  on the dedicated TV emulator.
- Home: from content card, fresh Up focused the Home top pill. Pass.
- Movies library: from first content card, first Up left focus on the content card; second Up focused the Movies pill. Not a clean pass.
- For You: from content, fresh Up focused the For You control. Pass.
- Calendar: targeted Calendar and entered content; Up focused the in-page `Following` control rather than the top Calendar pill. Not a clean top-pill pass.
- Hold-Up from lower row: not cleanly completed after the caveats above.
- Search route focus: not completed after the caveats above.
- TV hero metadata: visual/UIAutomator dumps showed editorial hero metadata for focused content and no technical badges in the large hero; content cards still show technical badges outside the hero.

Phone smoke on final phone APK:

- Installed `androidApp/build/outputs/apk/release/androidApp-universal-release.apk`
  on the dedicated phone emulator.
- Opened Libraries > Movies. The Movies library Recommended hero displayed editorial chips `2004`, `7.3`, `War`, `R`, with no generic `Movie` chip, and Play / More Info visible.
- The selected movie did not show a runtime chip in the UIAutomator dump, so the requested movie smoke is partial rather than a full pass.
- Episode carousel page smoke was not completed.
- Narrow viewport wrapping was not completed beyond the default `1080x2400` phone emulator viewport.

Smoke conclusion: not fully green. PR #126 was not pushed or updated.

## APK Artifacts

Built universal minified release APKs from local HEAD `9c251293` with `-PallowDebugReleaseSigning=true`.

Signing caveat: these are debug-signed release builds. They only upgrade installations signed with the same debug certificate.

Copied artifacts:

- `Silo-Phone-Universal-0.3.11-FocusHeroFix-9c251293.apk`
- `Silo-TV-Universal-0.3.11-TVFocusHeroFix-9c251293.apk`

SHA-256:

- Phone: `0c4c68d15d47c28de41ef1fc1db080ba266f899aa2c3dad663a20fdb5dd5ed50`
- TV: `9e135e3cdad20f1b000ae2ef55573ceb6070e1412718d4ebde47a1977e492a09`

`apksigner verify --verbose` on copied APKs:

- Phone: verifies; v2 scheme true; number of signers 1.
- TV: verifies; v2 scheme true; number of signers 1.

`apkanalyzer` status:

- `apkanalyzer` failed with `IllegalStateException: Cannot locate latest build tools`, even with `ANDROID_HOME` and `ANDROID_SDK_ROOT` set.
- Used SDK `aapt` as fallback for package/version/ABI metadata.

`aapt dump badging` metadata:

- Phone: `applicationId=org.siloserver.silo`, `versionCode=14`, `versionName=0.3.11`, native ABIs `arm64-v8a, armeabi-v7a, x86, x86_64`.
- TV: `applicationId=org.siloserver.silo`, `versionCode=15`, `versionName=0.3.11`, native ABIs `arm64-v8a, armeabi-v7a, x86, x86_64`.

## PR Status

No push performed.

No PR #126 description update performed because the required emulator smoke was not fully green.
