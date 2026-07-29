# PR #126 CodeRabbit Remediation Design

## Goal

Resolve every substantiated CodeRabbit finding on PR #126 without changing
deliberate request-sharing behavior or adding unrelated release scope.

## Scope and approach

The correction remains on `fix/tv-for-you-cold-navigation` and follows the
existing phone, TV, shared-repository, and Watch Together boundaries. Behavioral
changes are covered by focused regression tests before production changes.
Documentation and test-only cleanup remain separate from behavioral assertions
so failures identify the responsible correction.

The implementation will:

- Forward the shell Up fallback from the Alphabet library tab to its alphabet
  rail, matching the Browse tab.
- Advance the For You entry request with a `null` selection whenever the
  top-level For You destination is explicitly selected, so Watchlist or
  Favorites state is not retained.
- Make the initial TV marquee seed sensitive to section identity while
  preserving the rule that settled real focus always wins over a page-entry
  seed. A refresh may update a stale page-entry identity, but must not replace
  focused content.
- Accept IMDb ratings only when finite and within `(0, 10]` on both phone and
  TV. Invalid rating and invalid duration cases will be tested independently.
- Make Watch Together delivery-key nullability explicit at the latch and player
  reporting call sites without changing attach, cadence, or delivery semantics.
- Remove machine-specific paths from the committed SDD report and correct the
  stale plan references and wording identified by CodeRabbit.
- Apply small behavior-preserving helper extractions only where they directly
  address a review comment and reduce duplicated validation or test setup.
- Keep the existing shared in-flight recommendation request behavior. A
  superseded caller may discard its result, but it must not cancel work shared
  with another caller.

## Finding disposition

The Calendar fallback finding is already corrected on the current PR head and
will receive verification rather than another code change. The claimed
`RoomDeliveryLatch` compilation failure is disproven by both local compilation
and hosted Unit Tests; explicit null binding will nevertheless make the
invariant visible and remove the ambiguity that prompted the comment.

Generic requests to split the PR or increase repository-wide docstring coverage
are not defects in the changed behavior and are outside this remediation.

## Testing

Focused tests will cover:

- Alphabet and Browse fallback forwarding parity.
- Explicit For You root selection after Watchlist and Favorites entry.
- Marquee reseeding for a changed row identity, including protection of
  real-focused content.
- Phone and TV rating upper bounds.
- Mixed valid-duration/invalid-rating and invalid-duration/valid-rating cases.
- Nullable and mismatched Watch Together delivery keys.

After focused RED/GREEN cycles, the relevant phone, TV, shared, and
Android-shared unit suites will run, followed by the repository supply-chain
policy checks and phone/TV release compilation used by this branch. No APK will
be installed or deployed as part of this remediation.

## Completion criteria

The branch must be clean, all focused and full verification commands must pass,
an independent reviewer must report no unresolved critical or important issue,
and PR #126 must accurately reflect the added correction commit and current
check state. Proven false positives will be documented rather than addressed by
semantic changes.
