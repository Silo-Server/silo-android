# Section Cache Profile-Switch Test Race Design

**Date:** 2026-07-29
**Status:** Approved

## Problem

The post-merge `main` workflow failed
`SectionRepositoryCacheTest.homeRequestStartedBeforeProfileSwitchDoesNotServeOldProfileSectionsToNewProfile`
with `expected Old, got New`.

The shared `gatedRepository` test helper currently:

1. increments the request counter;
2. completes `requestEntered`, which can resume the waiting test coroutine;
3. derives the response body from the mutable counter.

The resumed coroutine can start request two between steps 2 and 3. Request one
then observes the second request's counter value and receives the wrong fixture.
This is a test-harness scheduling race; the failed branch and merge commit have
identical Git trees, and PR #130 did not change shared production or test code.

## Design

Capture each request's response body immediately after `onRequest()` and before
completing `requestEntered`. Only then expose the request to the waiting test and
block on `releaseResponse`.

This assigns the fixture deterministically to the request that incremented the
counter while preserving the helper's existing request-entry and release gates.

## Scope

- Modify only `gatedRepository` in
  `shared/src/commonTest/kotlin/org/siloserver/silo/repository/SectionRepositoryCacheTest.kt`.
- Do not change `SectionRepository`, identity-transition behavior, production
  code, timeouts, worker counts, or application binaries.
- Do not weaken or remove the profile-isolation assertions.

## Verification

- Use the hosted failure as the RED evidence: request one received `New`.
- Run the exact failed test repeatedly under `--max-workers=2 --rerun-tasks
  --no-daemon`.
- Run the complete `SectionRepositoryCacheTest` class.
- Run the complete shared debug unit suite under the hosted two-worker shape.
- Confirm the final diff is test-only and whitespace-clean.
