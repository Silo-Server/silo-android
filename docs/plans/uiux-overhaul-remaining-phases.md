# Silo Android UI/UX overhaul — remaining phases (6–8)

Handoff doc for picking up the UI/UX + performance overhaul in a new session.
Self-contained: read this top-to-bottom and you have everything needed to start.

- **Branch:** `uiux-fluidity-overhaul` (off `main`, base commit `17fb749`).
- **Status:** Phases 1–8 implemented + committed; both apps compile clean and
  build minified release. **Phases 7 & 8 are GATED** — they need the owner's
  on-device checkpoint (TV D-pad focus for 7; minified smoke test + baseline
  profile generation for 8) before merge.
- **⚠️ Test-suite caveat discovered during phase 7:** `:androidTvApp:testDebugUnitTest`
  had NOT compiled since `071e1c0` (orphaned watch-together test), so every TV
  source guard was silently dormant. Fixed in `a7d978b9` (suite now 371 green).
  The full `:androidApp:testDebugUnitTest` also has **11 pre-existing failures**
  (not 4): 7 are the `URISyntaxException` Windows path→URI env issue, 4 are a
  `PersonDetailViewModelTest` Integer→Long `ClassCastException` — all pre-date
  and are untouched by phases 6–8.
- **Owner priority (overriding all):** the app must FEEL fluid and fast. Weight
  perceived-speed/smoothness over feature breadth. A recurring pattern in this
  codebase is polish that was built but never wired up — favor connecting that.

---

## 0. Orientation — repo + stack

Kotlin Multiplatform. Modules:
- `androidApp/` — phone & tablet (Jetpack Compose, Material3)
- `androidTvApp/` — Android TV (Compose for TV, `androidx.tv:tv-material`)
- `android-shared/` — Android UI/infra shared by both (player backends, downloads, Room, sync, **`ThumbhashImage`/`ThumbHash`**)
- `shared/` — KMP commonMain + androidMain (Ktor networking, models, domain, some ViewModels)

Stack: Kotlin 2.1.20, Compose Multiplatform 1.7.3, Material3, Compose-for-TV 1.0.1,
Coil3 3.1.0, Media3/ExoPlayer 1.10.0 + libmpv, Koin DI, navigation-compose 2.9.0,
Room 2.8.4, DataStore, WorkManager. minSdk 24, targetSdk 35, compileSdk 36.
Package root `com.continuum.app` (dirs say "continuum"; product is "Silo").
iOS-parity is a deliberate design driver — many files say "Mirrors iosApp …".

---

## 1. ⚠️ Build & verify procedure (READ FIRST — a mistake was made here)

This is Windows + Git Bash. Android Studio keeps a Gradle daemon (different JVM)
that holds a file lock on `shared/build/.../classes.jar`. So:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # JDK 21
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SDK_ROOT="/c/Users/<you>/AppData/Local/Android/Sdk"
./gradlew --stop                                                  # stop daemons first
./gradlew <tasks> --no-daemon -Dorg.gradle.vfs.watch=false --console=plain
```

**CRITICAL VERIFICATION GOTCHA:** if you wrap the build in a script that ends with
`echo "BUILD EXIT: $?"`, the *background task's* reported exit code is the wrapper
echo's (always 0), NOT gradle's. **Always grep the gradle output for the real
result** — do not trust the task-notification exit code. Two phases (3, 4) were
committed un-compiling because this was trusted. Grep for:

```
^e:            # Kotlin compile errors
BUILD SUCCESSFUL | BUILD FAILED
tests completed, N failed
```

Fast full verification (both apps compile main+test + run source guards):
```bash
./gradlew :androidApp:testDebugUnitTest --tests "*SourceTest" \
          :androidTvApp:testDebugUnitTest --tests "*SourceTest" \
          --no-daemon -Dorg.gradle.vfs.watch=false --console=plain
```
Per-app compile only: `:androidApp:compileDebugKotlinAndroid`,
`:androidTvApp:compileDebugKotlinAndroid`.
Install: `:androidApp:installDebug` / `:androidTvApp:installDebug` (emulator runs
the **TV** image; `adb` at `…/Android/Sdk/platform-tools/adb.exe`).

**Pre-existing test failures (NOT yours, ignore):** `ReaderViewModelReaderTargetSourceTest`
(×3) and `EpubReflowSourceTest` (×1) fail with `java.net.URISyntaxException` — a
Windows path→URI environment issue in ebook code this branch never touched.

**Source-guard tests** (string-based, read source files, must stay green):
`androidApp/.../ui/performance/MobileListPerformanceSourceTest.kt`,
`androidTvApp/.../ui/components/TvSkylineSectionFeedSourceTest.kt`,
`androidTvApp/.../ui/performance/TvListPerformanceSourceTest.kt`,
`androidTvApp/.../ui/theme/TvSkylineTokenParityTest.kt`. If you change a file they
assert on, update the assertion in the same change.

---

## 2. What's already done (phases 1–5)

Commits on the branch (newest first): `a39ed45` fix opt-ins · `9a9ee3d` phase 5 ·
`6b93891` phase 4 · `30d9637` phase 3 · `dfa6e5c` phase 2b · `75f7fe6` phase 2a ·
`1e9e0cf` phase 1b · `a3f18b1` phase 1a. (Note: commits 3 & 4 don't compile in
isolation; the opt-in fix landed in `a39ed45`. Branch TIP is green. If you want a
clean history, squash on merge.)

Delivered: cold-launch-only splash (phone+TV); theme toggle + plumbing removed
(dark-only); Coil 512MB disk-cache `SingletonImageLoader.Factory`; **thumbhash
blur-up** (decoder in `android-shared/.../ui/components/ThumbHash.kt`, wired as
Coil placeholder in `ThumbhashImage.kt`); hero backdrop decode cap; content
**skeletons** (`androidApp/.../ui/components/Skeleton.kt` + Home/Detail/Browse/
Libraries); **QuickStart** buffer default (`ContinuumPlayerFactory.kt`); **buffered
scrubber** (`PlayerProgressBar.kt` + `bufferedPosition` plumbed through
`PlayerViewModel`/`PlayerScreen`/`PlayerOverlay`/`PlayerControls`); **TV smooth
scroll** (`TvSmoothBringIntoViewSpec` provided in `TvSkylineSectionFeed` +
`TvCatalogGrid`, manual double-scroll removed); **nav route collapse**
(`findStartDestination` anchor, deleted `video`/`audio`/`reading` aliases);
FeaturedCarousel per-frame `remember`; `animateItem` dismissals; inset-relative
top bar; top-bar a11y labels; dead code deleted.

Full original audit: 38 verified findings across 8 dimensions (the multi-agent
review). Phase-relevant findings are summarized in each phase below.

**Decisions already made in the requirements interview — do NOT re-ask:**
- Phase 6 nav motion → **shared-element hero** (chosen over plain slide / fade).
- Phase 7 TV focus → **refactor now** (chosen over leave / test-then-later), behind tests.
- Phase 8 build → **R8 + Baseline Profile, both now**.
- Declined entirely: audiobook poller lifecycle gate; Compose stability-config file.

Per-phase commit style; end commit messages with
`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## 3. Phase 6 — shared-element hero transitions (phone) ✅ DONE (`816d54c`)

**Shipped:** `SharedTransitionLayout` wraps the phone `NavHost`; the
`SharedTransitionScope` + each destination's `AnimatedVisibilityScope` are
published via CompositionLocals in new
`androidApp/.../ui/navigation/SharedElementTransition.kt`
(`LocalSharedTransitionScope` / `LocalNavAnimatedVisibilityScope`). A
`Modifier.heroSharedBounds(contentId)` helper tags both ends with key
`hero-$contentId`. **Used `sharedBounds` + `RemeasureToBounds`, not
`sharedElement`** — the two ends are different images (2:3 poster vs wide
backdrop), and `sharedBounds` is Android's prescribed API for visually-different
source/target: it crossfades the artwork while morphing the bounds. This is the
realization of the chosen "shared-element hero," not the slide/fade downgrade.
Sources wired: home poster rails (`MediaRow`) + detail "More Like This"
(`SimilarRail`); target: the `DetailHero` backdrop. Home→detail and
detail→detail both morph. Guarded by `MobileSharedElementSourceTest`.

⚠️ **Not visually verified** — the emulator runs the TV image, so the phone
morph needs a real phone to confirm/tune (e.g. boundsTransform spring). Safe by
construction otherwise: the helper no-ops when no scope is present, and duplicate
same-key posters degrade gracefully (verified against the 1.7.6 impl — no
at-rest glitch, no crash).

**Follow-up (one-liner per surface):** wrap the Libraries / For You / Browse
destinations + grids (`CatalogGrid`, `PersonalMediaGridContent`) and enroll the
continue-watching `BackdropCard` — each just needs its destination wrapped in
`CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this)` and the
content id passed to the card.

---

### Original phase 6 plan (for reference)

**Goal:** tapping a poster in a list morphs that poster into the hero/backdrop
image on the item-detail screen (and reverses on back). The premium "the thing I
tapped carried me here" feel. Today every screen change is nav-compose's flat
default fade.

**Approach (Compose 1.7 shared elements, experimental):**
1. Wrap the phone `NavHost` in `SharedTransitionLayout { … }` at
   `androidApp/.../ui/navigation/AppNavigation.kt` (the `NavHost(...)` call, ~line 107).
   `SharedTransitionLayout` provides a `SharedTransitionScope` receiver.
2. Expose the `SharedTransitionScope` to descendants via a CompositionLocal (e.g.
   `LocalSharedTransitionScope`) OR thread it as a param. Each `composable(route){…}`
   lambda's receiver is an `AnimatedContentScope` (an `AnimatedVisibilityScope`);
   capture it per destination (also via CompositionLocal, e.g.
   `LocalNavAnimatedVisibilityScope`, set inside each relevant `composable{}`).
3. On the **source** poster image and the **target** hero image, apply
   `Modifier.sharedElement(rememberSharedContentState(key = "hero-$contentId"),
   animatedVisibilityScope = <that destination's AnimatedVisibilityScope>)` — both
   must use the SAME key (the contentId) and be composed during the transition.

**Key files / where the two ends live:**
- Source poster: `androidApp/.../ui/components/MediaCard.kt` — the poster
  `ThumbhashImage` (~line 86). It's reached via HomeScreen → `HomeSectionRow` →
  `MediaRow` → `MediaCard`, and also `PersonalMediaGridContent`, `CatalogGrid`,
  `SimilarRail`. The contentId is available at each. (Decide scope: at minimum wire
  the Home rows + the detail "More like this" rail; grids are a stretch.)
- Target hero: item detail. `ItemDetailScreen.kt` dispatches to
  `MovieDetailContent.kt` / `SeriesDetailContent.kt`; the hero image composable is
  in `DetailSharedComponents.kt` (grep for the hero/backdrop `ThumbhashImage` or
  `HeroBackdrop`). Apply the matching `sharedElement` key there.

**Gotchas / risk (this is the large, finicky one):**
- Needs `@OptIn(ExperimentalSharedTransitionApi::class)`.
- Threading the two scopes (SharedTransitionScope + per-destination
  AnimatedVisibilityScope) down to `MediaCard` (deep) and the detail hero is the
  bulk of the work. CompositionLocals keep it from polluting every signature.
- Keys MUST match and both ends must be present during the transition or it
  silently no-ops or jumps. Test on-device; expect a round of iteration.
- ThumbhashImage already does a crossfade placeholder — make sure the shared
  element animates the image bounds, not fighting the crossfade.
- **Fallback if full shared-element proves too fiddly:** ship a directional
  slide + fade `NavHost` transition (enter/exit specs) as a solid baseline, and/or
  `sharedBounds` on just the hero image. Confirm with owner before downgrading —
  they explicitly chose shared-element.

**Verify:** compile both apps; run the app and watch list→detail→back on Home and
the similar rail. Watch for dropped frames / mismatched bounds.

---

## 4. Phase 7 — TV focus state-machine refactor ✅ IMPLEMENTED (GATED) — `e9184e20`

**Shipped:** consolidated the loose counters/booleans/effects into one holder,
`TvShellFocusState` (new `ui/shell/TvShellFocusState.kt`), with a derived `mode`
enum (Content / MenuFocused / ProfileMenu / Panel) and named transitions. The
4-way Back precedence + mode logic that the "fix focus" commits kept breaking is
extracted into PURE functions (`tvShellMode` / `tvShellBackAction`) and covered
by REAL unit tests (`TvShellFocusStateTest`, 15 cases). The Compose-side
focus-dispatch guards (UP-exit cancel, double-move prevention, runCatching,
centralized shell Back) are pinned by `TvShellFocusSourceTest` (each tied to its
origin fix commit). The nudge COUNTERS were retained (collapsing them into direct
FocusRequesters crosses the shell/child boundary — a follow-up). Behavior-
preserving; full TV unit suite green (371).

⚠️ **Owner checkpoint before merge:** D-pad focus *dispatch* is unverifiable off
a TV. Verify: Up from top content row → selected tab; dropdown open/close →
avatar; library-tab Down → cascade + Back closes it; menu-focused Back → content
(not app exit); cascade commit → scoped content.

### Original phase 7 plan (for reference)

## 4b. Phase 7 — TV focus state-machine refactor ⚠️ GATED

**Owner agreed to a checkpoint before merging this.** It's the riskiest file.

**Current state:** `androidTvApp/.../ui/shell/TvMainShell.kt` (~1000 lines)
hand-rolls focus/back coordination with ~5 mutable Int counters
(`menuFocusRequest`, `profileFocusRequest`, `contentFocusRequest`,
`panelFocusEntryToken`, `sectionRequestNonces` map) + booleans (`panelEntersFocus`,
`isMenuFocused`, `profileMenuOpen`, `openPanel`) + ~8 `LaunchedEffect`s + one outer
`onPreviewKeyEvent` that centralizes ALL Back handling (a 4-way `when`:
openPanel / profileMenuOpen / isMenuFocused / nestedNav fallthrough).
`TvTopMenuBar.kt` adds `lastHandledFocusRequest` + dwell timers + 3 more focus
effects. `TvCascadeSelector.kt` is related.

**Goal:** consolidate into ONE state holder (a remembered class or a ViewModel)
with an explicit enum of shell modes (e.g. Content / MenuFocused / ProfileMenu /
Panel) and named transitions, leaning on `focusRestorer()` / `focusGroup()` /
`FocusRequester` instead of N independent counters. Note `contentFocusRequest` is
already nearly vestigial (replaced by `focusRestorer`); `sectionRequestNonces` is a
content re-apply nonce (different concern — don't fold it into focus state).

**MANDATORY: test-first.** `TvMainShell.kt` has 34 commits, many titled "fix
focus." Before refactoring, write characterization/regression tests covering the
behaviors past fixes added (from git log):
- D-pad Up double-move / hidden-menu focus / hero Down crash
- Back at shell level so menu-focused Back doesn't exit the app
- cascade commit key-bleed
- UP-exit guard (geometric focus escape upward is cancelled; menu routing via
  `menuFocusRequest++`)
Existing guards to preserve: `runCatching` around every `requestFocus()`,
`focusProperties { exit/enter }`, `focusGroup()`, `>0` guards on focus counters.

**Risk:** high regression surface; remote D-pad behavior is hard to unit-test
(may need source-guard or instrumentation tests). Land tests, refactor, then
**checkpoint with owner** and verify on a real/emulated TV before merge.

---

## 5. Phase 8 — R8/minify + Baseline Profile ✅ IMPLEMENTED (GATED) — `d4fbba3e`

**Shipped:** `release` buildType on both apps (`isMinifyEnabled` +
`isShrinkResources`, debug-signed for installable smoke testing) sharing one root
`proguard-rules.pro` with keep rules for the reflection/JNI-heavy components
(kotlinx.serialization serializers under `com.continuum.app.**`, Koin ViewModels,
Media3 FFmpeg `Class.forName` renderer, libmpv JNI, BouncyCastle TLS-PSK, Room,
+ Ktor/OkHttp/Coil3/zxing dontwarns). `androidx.profileinstaller` in both apps;
new `:baselineprofile` macrobenchmark module (cold-start + scroll generator,
managed `pixel6Api34` device) targeting `:androidApp`; root `build.gradle.kts`
pins `com.android.test`/`kotlin-android`/`androidx.baselineprofile` apply-false so
the new module's plugins reconcile with the apps' shared AGP/Kotlin artifacts.

**Verified here:** both apps `assembleRelease` build clean through R8 (~35–42 MB
per-ABI); `:baselineprofile` configures, generator compiles, generation variants
assemble. (A Windows file-contention flake appears only when building *all*
release-type variants' ABI splits at once — use `--max-workers=3`; the real
generation flow builds one variant.)

⚠️ **Owner checkpoint before merge — both are device-gated:**
1. Install a minified release and smoke-test: boot + Media3 playback + MPV
   playback + LAN (TLS-PSK) pairing (R8 breakage is runtime-only).
2. Generate the profile on a device/emulator:
   `./gradlew :baselineprofile:generateBaselineProfile` (a richer logged-in
   Home-scroll journey needs a test server/account). Measure cold-start + size
   before/after.

### Original phase 8 plan (for reference)

## 5b. Phase 8 — R8/minify + Baseline Profile ⚠️ GATED

**Owner agreed to a checkpoint before declaring done.** Owner chose "both now".

**Current state:** neither `androidApp/build.gradle.kts` nor
`androidTvApp/build.gradle.kts` has a `buildTypes` block → release is unminified,
no resource shrinking. No Baseline Profile, no `profileinstaller`, no signingConfig
(pre-1.0, no release pipeline yet — this is launch-prep).

**Work:**
1. Add a `release` buildType to BOTH app modules with `isMinifyEnabled = true`,
   `isShrinkResources = true`, `proguardFiles(getDefaultProguardFile(
   "proguard-android-optimize.txt"), "proguard-rules.pro")`.
2. Author `proguard-rules.pro` keep-rules — this stack is **reflection-heavy**, so
   naive R8 WILL break things at runtime (silently). Cover:
   - Koin (DI)
   - kotlinx.serialization — keep `@Serializable` classes + generated serializers
   - Media3 / ExoPlayer — `DefaultRenderersFactory` reflects extension renderers
     (esp. the FFmpeg AAR path); keep media3 + the libmpv backend
   - BouncyCastle (TLS-PSK LAN pairing — `bcprov`/`bctls`)
   - Room (generated DAOs/db)
   - Coil3 (ServiceLoader fetchers/decoders)
   - zxing (QR)
3. Baseline Profile: add the `androidx.baselineprofile` Gradle plugin + a
   `:baselineprofile` macrobenchmark module (cold-start + home-scroll journeys),
   add `androidx.profileinstaller` to both app modules, register the module in
   `settings.gradle.kts`, add versions to `gradle/libs.versions.toml`.
4. **Smoke test (essential):** build a minified variant and verify it BOOTS, plays
   media (both Media3 and MPV paths), and LAN pairing works. R8 breakage shows up
   only at runtime. Measure cold-start + size before/after.

**Verify + checkpoint with owner.**

---

## 6. Quick-start checklist for the new session

1. `git checkout uiux-fluidity-overhaul` and read recent commits (`git log --oneline -10`).
2. Re-read this doc + the memory note `silo-uiux-overhaul-plan` and `silo-build-on-windows`.
3. Confirm the branch tip still compiles green using the §1 procedure (grep the output!).
4. Pick a phase. 6 is non-gated; 7 and 8 require an owner checkpoint before merge.
5. Work in per-phase commits; keep source-guard tests green; verify by grepping
   gradle output, never the task exit code.
