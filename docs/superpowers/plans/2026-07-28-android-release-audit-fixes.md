# Android Release Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the three Important Android phone release bugs found by the final audit: stale Libraries responses, Browse content behind bottom chrome, and cross-identity cache attribution.

**Architecture:** Libraries request families will use generation tokens owned by `LibrariesViewModel`, allowing superseded asynchronous completions to be discarded without changing repository APIs. `CatalogGrid` will accept the already-measured bottom chrome inset and reserve it for both grid content and the alphabet rail. Offline cache writes will preserve request-time identity ownership by rejecting writes after the shared identity generation changes.

**Tech Stack:** Kotlin, coroutines/StateFlow, Jetpack Compose, Room-backed Android catalog cache, Kotlin test/coroutines-test, Gradle.

## Global Constraints

- Android phone and shared Android code only; no server/API/schema/protocol changes.
- Preserve existing successful response, offline fallback, paging, and identity-transition behavior.
- Tests must deterministically complete deferred requests out of order; no sleeps or widened timeouts.
- Physical devices remain excluded.
- Every production correction must be preceded by a failing regression.

---

### Task 1: Make Libraries Results Current and Keep Browse Above Bottom Chrome

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/browse/CatalogGrid.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesViewModelTest.kt`
- Test: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibraryChromeInsetSourceTest.kt`

**Interfaces:**
- Consumes: `LocalBottomChromeInset.current`, the existing `LibrariesUiState` selected library/query fields, and existing repository suspend functions.
- Produces: monotonically increasing Recommended, Browse, and Collections request generations; `CatalogGrid(..., bottomContentInset: Dp = 0.dp)` whose grid and alphabet rail stay above that inset.

- [ ] **Step 1: Write deferred-response regressions**

Add deterministic tests that start request A, change library or Browse query state, start request B, complete B first, then A, and assert the final rows/grid still belong to B. Cover Recommended, Browse sort/filter, and Collections.

- [ ] **Step 2: Verify the request regressions fail**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests org.siloserver.silo.android.ui.screens.libraries.LibrariesViewModelTest \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: the reverse-completion assertions fail because A overwrites B.

- [ ] **Step 3: Implement request generations**

Keep one counter per request family:

```kotlin
private var recommendedRequestGeneration = 0L
private var catalogRequestGeneration = 0L
private var collectionsRequestGeneration = 0L
```

Increment and capture the family generation before launching work. Before every success/error/loading completion write, require both the captured generation and the captured library/query identity to remain current. Superseded requests may finish, but must not mutate `uiState`.

- [ ] **Step 4: Write the bottom-inset regression**

Extend `LibraryChromeInsetSourceTest` to require `BrowseTabContent` to pass `LocalBottomChromeInset.current` into `CatalogGrid`, and require `CatalogGrid` to expose and consume `bottomContentInset` in both its scroll padding and alphabet-rail bounds.

- [ ] **Step 5: Verify the inset regression fails**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests org.siloserver.silo.android.ui.screens.libraries.LibraryChromeInsetSourceTest \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: failure because `CatalogGrid` currently hard-codes 8dp bottom padding and the full-height rail.

- [ ] **Step 6: Implement the measured bottom inset**

Add:

```kotlin
bottomContentInset: Dp = 0.dp
```

to `CatalogGrid`. Add it to grid/list bottom `contentPadding`, and constrain/pad the alphabet rail so its interactive range ends above the same inset. Pass `LocalBottomChromeInset.current` from Libraries Browse; standalone callers retain the zero default.

- [ ] **Step 7: Run focused GREEN verification and commit**

Run both focused test classes and relevant Android compilation. Commit only Task 1 files with:

```bash
git commit -m "fix(android): keep library results and browse chrome current"
```

### Task 2: Preserve Request-Time Identity for Offline Cache Writes

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/cache/RoomCatalogCacheRepository.kt`
- Modify only if required by the narrow ownership boundary: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/CatalogRepository.kt`
- Modify only if required by the narrow ownership boundary: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/SectionRepository.kt`
- Test: existing Room/cache repository Android unit tests and `shared/src/commonTest/kotlin/org/siloserver/silo/repository/CatalogRepositoryDetailCacheTest.kt`

**Interfaces:**
- Consumes: `IdentityTransitionBarrier.generation` and the existing cache snapshot/provider.
- Produces: cache writes that are accepted only when the request-time identity generation is still current; reads and offline fallback remain unchanged.

- [ ] **Step 1: Write the delayed identity-switch regression**

Create a deferred API response under identity A, switch the barrier/snapshot to B, complete A, then assert A's response is not written or readable as B. Cover item detail and one section/catalog path that exercises the shared ownership seam.

- [ ] **Step 2: Verify the ownership regression fails**

Run the exact new cache/repository tests with:

```bash
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  --tests '*CatalogRepositoryDetailCacheTest*' \
  --tests '*RoomCatalogCacheRepository*' \
  --rerun-tasks --max-workers=2 --no-daemon
```

Expected: failure showing the A response attributed to B.

- [ ] **Step 3: Implement generation-validated writes**

Capture `IdentityTransitionBarrier.generation` before each network-backed cacheable request. At completion, call the existing cache write only when the captured generation still equals the current generation. Do not synthesize a new identity from completion-time state and do not alter offline-read fallback semantics.

- [ ] **Step 4: Run focused GREEN verification and commit**

Run the new regressions plus neighboring cache/repository tests. Commit only Task 2 files with:

```bash
git commit -m "fix(shared): keep cache writes identity scoped"
```

### Task 3: Integrate and Requalify the Release

**Files:**
- Verify only: all files changed by Tasks 1 and 2.

**Interfaces:**
- Consumes: both reviewed fix commits.
- Produces: one clean branch with green supply-chain, phone/TV unit, and phone/TV release gates.

- [ ] **Step 0: Preserve request-time ownership for Home cache writes**

Add a Home-cache write lease using the shared identity generation, propagate it
from the section request through both `StartupWarmup` and `HomeViewModel`, and
make `RoomHomeCacheRepository` reject stale leases before and after resolving
the identity snapshot. First add a deterministic delayed A→B regression that
proves A's Home sections cannot be stored or read as B. Preserve existing
offline Home reads and successful same-identity warmup behavior.

- [ ] **Step 1: Review each task diff independently**

Require explicit spec-compliance and code-quality approval; fix every Critical, Important, or Minor release finding before continuing.

- [ ] **Step 2: Run the exact release gate**

```bash
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidApp:assembleRelease \
  :androidTvApp:assembleRelease \
  -PallowDebugReleaseSigning=true \
  --rerun-tasks --max-workers=2 --no-daemon
```

- [ ] **Step 3: Perform whole-branch review and artifact verification**

Require a clean `origin/main...HEAD` review, verify universal APK package/version/ABIs/v2 signature/size/SHA-256, copy final-hash artifacts without overwrite, then push and update PR #126 without merging.
