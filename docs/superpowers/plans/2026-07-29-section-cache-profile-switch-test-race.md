# Section Cache Profile-Switch Test Race Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the deterministic scheduling race in the shared section-cache test helper without changing production behavior.

**Architecture:** Keep the existing gated mock engine and its two synchronization points. Assign the response fixture to the current request before notifying the waiting test coroutine, so later requests cannot change that request's fixture through the shared counter.

**Tech Stack:** Kotlin, kotlinx.coroutines-test, Ktor MockEngine, Kotlin Test/JUnit, Gradle.

## Global Constraints

- Modify only the shared test helper.
- Make no production-code, timeout, worker-count, or application-binary changes.
- Preserve all existing profile-isolation assertions.
- Do not add sleeps, retries, or timeout widening.

---

### Task 1: Order Fixture Capture Before Entry Notification

**Files:**
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/repository/SectionRepositoryCacheTest.kt:56-61`

**Interfaces:**
- Consumes: `onRequest: () -> Unit`, `body: () -> String`, `requestEntered: CompletableDeferred<Unit>`.
- Produces: the same `gatedRepository(...)` helper signature and behavior with deterministic per-request fixture capture.

- [x] **Step 1: Preserve RED evidence**

Record the hosted failure from workflow `30453920065`:

```text
expected:<[Old]> but was:<[New]>
at SectionRepositoryCacheTest.kt:218
```

- [x] **Step 2: Apply the minimal ordering fix**

Change the MockEngine body from:

```kotlin
onRequest()
requestEntered.complete(Unit)
val responseBody = body()
releaseResponse.await()
```

to:

```kotlin
onRequest()
val responseBody = body()
requestEntered.complete(Unit)
releaseResponse.await()
```

- [x] **Step 3: Verify the exact regression repeatedly**

Run the exact test at least five times:

```bash
for run in 1 2 3 4 5; do
  ./gradlew :shared:testDebugUnitTest \
    --tests 'org.siloserver.silo.repository.SectionRepositoryCacheTest.homeRequestStartedBeforeProfileSwitchDoesNotServeOldProfileSectionsToNewProfile' \
    --max-workers=2 --rerun-tasks --no-daemon
done
```

Expected: all five runs pass.

- [x] **Step 4: Verify the containing class and shared suite**

```bash
./gradlew :shared:testDebugUnitTest \
  --tests 'org.siloserver.silo.repository.SectionRepositoryCacheTest' \
  --max-workers=2 --rerun-tasks --no-daemon

./gradlew :shared:testDebugUnitTest \
  --max-workers=2 --rerun-tasks --no-daemon
```

Expected: both commands pass.

- [x] **Step 5: Inspect and commit**

```bash
git diff --check
git diff -- shared/src/commonTest/kotlin/org/siloserver/silo/repository/SectionRepositoryCacheTest.kt
git add \
  docs/superpowers/specs/2026-07-29-section-cache-profile-switch-test-race-design.md \
  docs/superpowers/plans/2026-07-29-section-cache-profile-switch-test-race.md \
  shared/src/commonTest/kotlin/org/siloserver/silo/repository/SectionRepositoryCacheTest.kt
git commit -m "test(shared): stabilize profile-switch request fixture"
```
