# Android Final Ledger Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every current defect validated from the independent 2026-07-28 Android end-to-end ledger before publishing release candidates.

**Architecture:** Watch Together reconciliation remains centralized in its existing repository and lobby surfaces. Audiobook teardown reuses the application-owned playback lifecycle for asynchronous externally-owned session finalization. The three remaining isolated fixes stay at their current subtitle, authentication, and download ownership boundaries.

**Tech Stack:** Kotlin, coroutines/Flow, Ktor, Jetpack Compose, Media3, Room, WorkManager, Kotlin test/coroutines-test, Gradle.

## Global Constraints

- Android phone and TV only; no server/API/schema/protocol changes.
- Preserve all already-reviewed Watch Together generation, lease, reconnect, and leave semantics.
- No `runBlocking` on UI/ViewModel teardown, arbitrary sleeps, timeout widening, or fire-and-forget global scopes.
- Every behavioral fix starts with a deterministic RED regression.
- Physical devices remain excluded.
- Delete only the verified-unused no-op auth stub; do not alter the installed real auth plugin.

---

### Task 1: Reconcile Watch Together Suggestions and Surface Lobby Errors

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/watchtogether/WatchTogetherRepository.kt`
- Modify: phone and TV Watch Together lobby ViewModels/screens as required by their existing error-effect architecture.
- Test: `shared/src/commonTest/kotlin/org/siloserver/silo/watchtogether/WatchTogetherRepositoryTest.kt`
- Test: phone and TV lobby unit/source tests.

**Interfaces:**
- Consumes: existing `Opened`, REST `listSuggestions`, repository `errors`, lobby message/snackbar effects.
- Produces: reconnect hydration, authoritative `votedIds`, and visible transient lobby failures on both clients.

- [ ] **Step 1: Write reconnect hydration RED**

Start a socket, publish suggestions, terminate transport, mutate the fake REST
suggestion list, connect the successor socket, emit `Opened`, and assert REST
is called again and the missed mutation is published. Verify a protocol
`Closed` remains terminal and does not refresh/reconnect.

- [ ] **Step 2: Write authoritative unvote RED**

Apply a REST list with `votedByMe=true`, then refresh with the same suggestion
set to false. Assert the repository's local vote set removes that ID while
preserving votes still reported true.

- [ ] **Step 3: Implement repository reconciliation**

On each healthy `Opened`, refresh suggestions through the existing REST API
without creating a second repository/socket architecture. Replace the
authoritative vote set from REST responses rather than only OR-ing true IDs;
retain optimistic mutation behavior between authoritative refreshes.

- [ ] **Step 4: Write lobby error RED**

Make vote/promote/suggest fail in each lobby ViewModel and assert one visible
message effect. Also assert repository transient socket errors are visible
while the lobby is active.

- [ ] **Step 5: Implement phone/TV error parity**

Collect the existing repository error flow in the existing lobby lifecycle,
and translate rejected operations through the same one-shot UI message path.
Do not add replay that can show stale errors after navigation.

- [ ] **Step 6: Run focused GREEN and commit**

Run repository and both lobby test suites, compile phone/TV, then commit:

```bash
git commit -m "fix(watch-together): reconcile rooms and surface lobby errors"
```

### Task 2: Make Audiobook Teardown Non-Blocking

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionLifecycle.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/AudiobookPlayerViewModel.kt`
- Modify: phone and TV DI factories for the shared audiobook ViewModel.
- Test: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionLifecycleTest.kt`
- Test: a source-contract test for audiobook teardown.

**Interfaces:**
- Consumes: the lifecycle's existing application scope, `NonCancellable + Dispatchers.IO`, and `PlaybackSessionManager.reportProgress/stopSession`.
- Produces: `reportAndStopExternalSessionAsync(sessionId, positionSeconds, isPaused)` for an externally-owned audiobook session.

- [ ] **Step 1: Write asynchronous finalization RED**

Use a fake manager whose progress call suspends. Invoke the new external
finalizer, assert the caller returns before the fake is released, then assert
the exact session/position/pause report precedes stop.

- [ ] **Step 2: Write audiobook source-contract RED**

Assert `AudiobookPlayerViewModel.onCleared` contains no `runBlocking` and
submits a synchronously captured active session/position/pause snapshot to the
external finalizer.

- [ ] **Step 3: Implement the smallest lifecycle reuse**

Add a coalesced application-scope async operation for the explicit external
session ID. Do not adopt the audiobook session into the video lifecycle and do
not derive the target from lifecycle-owned state. Inject the lifecycle through
both clients' existing factories.

- [ ] **Step 4: Run focused GREEN and commit**

Run lifecycle/audiobook tests and phone/TV compilation, then commit:

```bash
git commit -m "fix(android): finalize audiobook sessions asynchronously"
```

### Task 3: Correct TV Top Subtitle Title-Safe Compensation

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt`
- Test: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt`

**Interfaces:**
- Consumes: subtitle vertical preset and `titleSafeFraction`.
- Produces: unchanged Top base padding; compensated Bottom/LowerThird padding.

- [ ] **Step 1: Write RED**

With `titleSafeFraction = 0.05f`, assert Top remains its base `0.74f` padding
while Bottom and LowerThird retain their existing compensation.

- [ ] **Step 2: Implement GREEN**

Skip bottom-padding subtraction only for the Top preset; preserve every other
preset and the outer title-safe surface inset.

- [ ] **Step 3: Verify and commit**

Run the full subtitle appearance class and compile both clients:

```bash
git commit -m "fix(tv): avoid double-shifting top subtitles"
```

### Task 4: Remove the Unused No-Op Auth Plugin

**Files:**
- Delete: `shared/src/commonMain/kotlin/org/siloserver/silo/network/AuthInterceptor.kt`

**Interfaces:**
- Consumes: repository-wide proof that only `SiloAuthPlugin` is installed.
- Produces: no public no-op authentication symbol that can be installed accidentally.

- [ ] **Step 1: Prove non-use**

Search production/tests/build publication metadata for `SiloAuth`; require the
declaration to be the only match and the real `SiloAuthPlugin` installation to
remain in `SiloHttpClientImpl`.

- [ ] **Step 2: Delete and compile**

Delete only the stub, compile shared/phone/TV, and commit:

```bash
git commit -m "chore(shared): remove unused no-op auth plugin"
```

### Task 5: Clear Permanent Download Failure Progress

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/downloads/DownloadWorker.kt`
- Test: the existing DownloadWorker/sidecar status test suite.

**Interfaces:**
- Consumes: existing live-record and sidecar status update helpers.
- Produces: nullable “preserve” byte arguments, with explicit zero clearing both stored representations on permanent failure.

- [ ] **Step 1: Write RED**

Seed a download and sidecar with nonzero bytes, invoke the permanent-failure
transition, and assert `Failed`, `bytesSent == 0`, and `fileSize == 0` in both.

- [ ] **Step 2: Implement GREEN**

Distinguish omitted/preserve values from explicit zero using nullable
arguments or an equally explicit update type. Preserve existing callers that
intend to retain progress.

- [ ] **Step 3: Verify and commit**

Run the focused worker/sidecar tests and compile phone/TV:

```bash
git commit -m "fix(android): clear progress on permanent download failure"
```

### Task 6: Final Integrated Release Qualification

**Files:**
- Verify only: `origin/main...HEAD`.

**Interfaces:**
- Consumes: Tasks 1–5 with independent approval.
- Produces: clean reviewed branch, green supply-chain/unit/release gates, final universal APKs, and updated PR #126.

- [ ] **Step 1: Independently review every task and fix every finding**

- [ ] **Step 2: Run supply-chain and exact combined phone/TV unit/release gate**

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

- [ ] **Step 3: Require a final whole-branch clean review**

- [ ] **Step 4: Verify and copy final universal APKs, then push/update PR #126 without merging**
