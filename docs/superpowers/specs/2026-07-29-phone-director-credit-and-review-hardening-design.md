# Phone Director Credit and Review Hardening Design

**Date:** 2026-07-29
**Target:** PR #129 (`feat/tv-detail-director-credit`)
**Base behavior:** PR #128 is already on `main`; PR #129 adds the TV movie-director credit.

## Goal

Bring PR #129's movie-director credit to Android phone with exact phone/TV parity, and close the concrete review gaps found while auditing PRs #128 and #129. The change must remain Android-client-only and must not alter server APIs, catalog models, or production purge behavior.

## User-visible behavior

For movie detail pages on both phone and TV, show one muted, single-line credit:

`Directed by Name One, Name Two`

The credit appears directly below the synopsis and optional description translation, and directly above the existing facts row. It is hidden for series, seasons, episodes, audiobooks, and other item types.

Director selection uses crew entries whose trimmed job is exactly `Director`, case-insensitively. Names are trimmed, blank names are removed, duplicate names are removed while preserving server order, and at most three names are shown. Jobs such as `Director of Photography` do not qualify. The existing title-detail layout, cast/crew section, actions, and navigation remain unchanged.

## Architecture

Move the pure director-selection and text-formatting rule to a small Android-shared presentation helper used by both `androidApp` and `androidTvApp`. This gives the two Android clients one rule without moving display policy into the shared catalog model. The phone and TV hero composables retain platform-specific typography and layout, but receive the same nullable formatted string.

The phone `DetailHero` gains an optional director-credit parameter. `MovieDetailContent` supplies the shared helper result; series and other phone detail paths do not. TV replaces its local extraction implementation with the shared helper and preserves its existing rendering.

## Review hardening

### PR #129 director coverage

Focused pure tests will cover:

- movie-only behavior;
- exact, case-insensitive `Director` job matching;
- trimmed names;
- blank-name removal;
- stable de-duplication;
- the three-name cap; and
- no credit when no qualifying director exists.

Phone and TV source/wiring tests will ensure both movie hero paths use the shared credit and that the existing TV and phone placement remains between synopsis/translation and facts.

### PR #128 runtime coverage

Existing phone and TV hero-metadata tests will be extended to prove that:

- positive catalog `runtime` minutes take precedence over playback `durationSeconds`; and
- absent or invalid catalog runtime falls back to `durationSeconds`.

This adds regression coverage only; PR #128's shipped production behavior is not redesigned.

### Hosted purger-test race

The failed hosted Unit tests check is a baseline test-harness race, not a PR #129 production regression. The test currently asserts that the removed server's row still exists after the second purge may already have deleted it.

The test will gain explicit deferred gates around the second purge's row deletion. It will wait until the second pass is demonstrably selected, assert the row still exists while deletion is held, release deletion, then assert the row is removed. This makes the intended snapshot/second-pass ordering deterministic without widening a timeout or changing `OrphanedServerDataPurger` production semantics.

## Verification

Verification will include the focused director, phone runtime, TV runtime, and purger tests; the relevant module unit-test tasks; Android phone and TV compilation; supply-chain policy checks required by the repository; and a diff audit confirming no server/protocol or production purge changes.

PR #129 will be updated but not merged.

## Non-goals

- Director credits for series, seasons, episodes, or audiobooks.
- Changes to server crew metadata or API contracts.
- Changes to the full cast/crew section.
- New navigation or detail-page structure.
- Production purge behavior changes.
- Timeout increases or retries that conceal the hosted test race.
