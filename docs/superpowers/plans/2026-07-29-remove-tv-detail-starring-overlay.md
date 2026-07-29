# Remove Android TV Detail Starring Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the duplicated floating `Starring …` credit from the Android TV detail hero while preserving the lower cast/crew section and the shared movie director credit.

**Architecture:** Delete the starring presentation at its existing TV-only boundaries: metadata derivation, detail-screen wiring, and hero rendering. Add one focused source-contract regression that proves those boundaries stay absent without changing cast models, the cast rail, phone UI, or any server-facing behavior.

**Tech Stack:** Kotlin 2.1, Jetpack Compose for TV, Kotlin Test/JUnit, Gradle, Android Debug Bridge for an emulator-only smoke check.

## Global Constraints

- Remove only the Android TV detail hero's floating upper-right `Starring …` overlay.
- Preserve `TvCastCrewSection` as the complete TV cast and crew presentation.
- Preserve the movie-only `Directed by …` credit on Android TV and phone.
- Do not add a replacement shadow, glyph halo, vignette, panel, or inline actor-credit row.
- Preserve existing title-detail content, actions, focus behavior, hero gradients, synopsis, translation, and fact tokens.
- Make no phone production UI, server, API, model, persistence, navigation, playback, protocol, or Apple-client changes.
- Do not refactor unrelated hero layout or metadata formatting.

---

## File Map

- Modify `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt`: remove the `starringText` API, upper-right overlay, and obsolete KDoc.
- Modify `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt`: stop deriving and passing the starring credit.
- Modify `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt`: remove the unused `starringText(ItemDetail): String?` formatter.
- Create `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvStarringOverlaySourceTest.kt`: guard the three production boundaries against reintroducing the duplicate overlay.
- Preserve `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvCastCrewSection.kt`: no edits; its presence on the detail page is checked during review and smoke validation.
- Preserve `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDirectorCreditSourceTest.kt`: no edits; its existing tests continue to protect the director-credit call and ordering.

### Task 1: Remove the TV Hero Starring Presentation

**Files:**
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvStarringOverlaySourceTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt:55-98,160-190`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt:433-452`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt:80-85`
- Verify unchanged: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvCastCrewSection.kt`
- Test unchanged: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDirectorCreditSourceTest.kt`

**Interfaces:**
- Consumes: `TvDetailHero(...)`, `TvDetailMetadata`, and the existing `TvCastCrewSection(...)` call in `TvItemDetailScreen`.
- Produces: `TvDetailHero(...)` without a `starringText: String?` parameter; `TvDetailMetadata` without `starringText(ItemDetail): String?`.

- [ ] **Step 1: Write the failing source-contract regression**

Create `TvStarringOverlaySourceTest.kt`:

```kotlin
package org.siloserver.silo.tv.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvStarringOverlaySourceTest {
    private val hero = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt",
    ).readText()
    private val screen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()
    private val metadata = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt",
    ).readText()

    @Test
    fun tvDetailDoesNotDeriveOrRenderDuplicatedStarringOverlay() {
        assertFalse(hero.contains("starringText"))
        assertFalse(screen.contains("TvDetailMetadata.starringText(detail)"))
        assertFalse(metadata.contains("fun starringText("))
    }

    @Test
    fun tvDetailStillRendersTheFullCastSection() {
        assertTrue(screen.contains("TvCastCrewSection("))
    }
}
```

- [ ] **Step 2: Run the new test and verify RED**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests org.siloserver.silo.tv.ui.screens.detail.TvStarringOverlaySourceTest \
  --no-daemon
```

Expected: `tvDetailDoesNotDeriveOrRenderDuplicatedStarringOverlay` fails because the current hero, screen, and metadata formatter still contain `starringText`. The cast-section assertion passes.

- [ ] **Step 3: Remove the metadata formatter**

Delete this function from `TvDetailMetadata.kt`:

```kotlin
fun starringText(detail: ItemDetail): String? {
    val names = detail.cast.take(3).map { it.name.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return null
    return "Starring ${names.joinToString(", ")}"
}
```

Do not alter any other metadata token formatting.

- [ ] **Step 4: Remove the detail-screen wiring**

Delete only this argument from the `TvDetailHero` call in `TvItemDetailScreen.kt`:

```kotlin
starringText = TvDetailMetadata.starringText(detail),
```

Leave the adjacent director call intact:

```kotlin
directorText = movieDirectorCredit(detail),
```

Leave the existing `TvCastCrewSection(...)` call unchanged.

- [ ] **Step 5: Remove the hero API and overlay**

In `TvDetailHero.kt`:

1. Remove `starringText: String?` from the `TvDetailHero` parameters.
2. Delete the complete `starringText?.takeIf { ... }` composable block.
3. Rewrite the class KDoc so the layout description ends with the bottom-anchored editorial and action column; remove the claim that a starring credit floats in the upper-right.
4. Keep `TextAlign`, `TextStyle`, `Shadow`, `Offset`, and `widthIn` imports because the remaining title, metadata, and editorial code still uses them.

The resulting signature around the affected parameters must be:

```kotlin
factsLine: List<TvHeroFactToken>,
directorText: String?,
actions: @Composable () -> Unit,
```

- [ ] **Step 6: Run the focused tests and verify GREEN**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests org.siloserver.silo.tv.ui.screens.detail.TvStarringOverlaySourceTest \
  --tests org.siloserver.silo.tv.ui.screens.detail.TvDirectorCreditSourceTest \
  --tests org.siloserver.silo.tv.ui.screens.detail.TvDetailMetadataTest \
  --no-daemon
```

Expected: all selected tests pass. In particular, the new regression finds no starring derivation or rendering, the existing director-credit source tests keep passing, and unrelated metadata behavior is unchanged.

- [ ] **Step 7: Inspect the production diff**

Run:

```bash
git diff --check
git diff -- \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvStarringOverlaySourceTest.kt
rg -n "starringText|Starring …|Starring " \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail
```

Expected: `git diff --check` succeeds; the diff contains only the scoped deletions plus the regression test; `rg` finds no production starring overlay or formatter. A match inside the new negative source-contract test is expected.

- [ ] **Step 8: Commit the coherent behavior change**

```bash
git add \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvStarringOverlaySourceTest.kt
git commit -m "fix(tv): remove duplicated hero starring overlay"
```

### Task 2: Verify the TV Detail Experience

**Files:**
- Verify unchanged: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvCastCrewSection.kt`
- Verify unchanged: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt`
- Verify unchanged: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt`
- Verify: all files changed by Task 1

**Interfaces:**
- Consumes: the Task 1 `TvDetailHero(...)` signature without `starringText`.
- Produces: verification evidence that the TV app compiles, the cast rail remains, the movie director credit remains, and no phone production code changed.

- [ ] **Step 1: Run the repository supply-chain checks**

Run:

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Expected: both scripts exit successfully without modifying dependency verification metadata.

- [ ] **Step 2: Run the complete Android TV unit suite**

Run:

```bash
./gradlew :androidTvApp:testDebugUnitTest --max-workers=2 --no-daemon
```

Expected: `BUILD SUCCESSFUL` with no Android TV unit-test failures.

- [ ] **Step 3: Compile both Android TV variants**

Run:

```bash
./gradlew \
  :androidTvApp:assembleDebug \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --max-workers=2 \
  --no-daemon
```

Expected: `BUILD SUCCESSFUL`. This is a compile/signing gate only; do not distribute the debug-signed release artifact.

- [ ] **Step 4: Confirm platform and scope boundaries**

Run:

```bash
git diff origin/main...HEAD --name-only
git diff origin/main...HEAD -- androidApp shared android-shared
rg -n "TvCastCrewSection\\(|directorText = movieDirectorCredit\\(detail\\)" \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt
```

Expected:

- the branch diff contains the approved spec, plan, three TV production files, and one TV test;
- the phone/shared diff is empty;
- both the TV cast section and director-credit wiring remain present.

- [ ] **Step 5: Perform a dedicated TV-emulator smoke check**

First prove that `emulator-5554` is the dedicated TV emulator before using it:

```bash
adb -s emulator-5554 get-state
adb -s emulator-5554 shell getprop ro.boot.qemu.avd_name
adb -s emulator-5554 shell getprop ro.build.characteristics
```

Proceed only if the device state is `device`, the AVD name is `Silo_TV`, and characteristics include `tv`. Do not issue ADB commands to any physical serial.

Install and launch the debug build:

```bash
adb -s emulator-5554 install -r \
  androidTvApp/build/outputs/apk/debug/androidTvApp-universal-debug.apk
adb -s emulator-5554 shell monkey \
  -p org.siloserver.silo \
  -c android.intent.category.LEANBACK_LAUNCHER \
  1
```

Using the existing emulator profile, open a movie detail page and verify:

1. no floating `Starring …` credit appears in the upper-right;
2. the backdrop remains unobstructed there;
3. `Directed by …` remains below synopsis/translation and above facts;
4. scrolling reaches the unchanged cast and crew section;
5. hero actions, directional focus, Back, and body scrolling behave normally.

If `emulator-5554` is absent, offline, not `Silo_TV`, or requires destructive profile setup, do not substitute a physical device; record this single visual gate as pending.

- [ ] **Step 6: Request focused code review**

Provide the reviewer:

- the approved design spec;
- this implementation plan;
- `git diff origin/main...HEAD`;
- focused/full test and build outputs;
- emulator evidence or the explicitly pending emulator gate.

The review question is: does the branch remove every TV detail starring boundary while preserving cast/crew, director credit, phone scope, and existing focus/layout behavior?

Address only findings that violate the approved scope or reveal a correctness regression. Re-run the smallest affected test after each correction, then repeat Steps 1–4 before completion.

- [ ] **Step 7: Record final verification state**

Run:

```bash
git status --short --branch
git log --oneline --decorate origin/main..HEAD
git diff --check origin/main...HEAD
```

Expected: the worktree is clean; the branch contains the spec commit, plan commit, and implementation commit; the final diff has no whitespace errors. Do not merge or deploy as part of this plan.
