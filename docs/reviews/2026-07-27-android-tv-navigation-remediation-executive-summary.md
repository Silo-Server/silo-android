# Android TV Navigation Remediation — Executive Summary

## Decision

Ship the cache-first Android TV navigation changes for external testing. The
change addresses the cold-start navigation slowdown and the broken `For You`
focus transition reported in issues #121 and #122 without changing server
contracts, playback behavior, or subtitle handling.

## Customer impact

On a large production library, the first trip across Home rows caused bursts of
detail requests and garbage collection while focus was moving. Returning over
the same rows was substantially faster because those details were then cached.
The `For You` filter-to-card transition also lacked an explicit focus bridge,
which could leave Down navigation stuck in the filter controls.

External testing of the first build found two smaller consistency issues:
holding Up while returning through several Home rows could carry focus straight
into the top menu, and Watchlist/Favorites selected from the For You dropdown
opened visually different standalone pages from the equivalent in-page pills.

## Verified causes

- Home inline sections could be hydrated more than once.
- Marquee enrichment and Skyline prefetch treated a cold detail cache as an
  invitation to fan out requests during focus movement.
- Skyline work could outlive or race the focus state that requested it.
- `For You` did not explicitly transfer focus between its filters and first
  result card.
- The Home row fallback accepted overlapping off-screen relocation requests and
  treated a repeated Up event at row zero like a deliberate fresh menu-entry
  press.
- The For You dropdown routed saved lists to standalone destinations while the
  in-page pills used inline grids.

Archived PR #108 contained earlier versions of the relevant performance ideas
in commits `49a70045` and `65c4b316`, but those commits were not ancestors of
current `main`. This branch forward-ports the behavior against the current
architecture with focused tests and bounded concurrency.

## Changes

- Hydrate shared inline Home sections once.
- Make marquee detail enrichment cache-first.
- Start Skyline prefetch only after focus settles and cancel obsolete work.
- Add explicit, tested `For You` filter/card focus transitions.
- Serialize off-screen Up relocation, stop held/repeated Up on Home's first
  content row, and require a fresh Up press to enter the top menu.
- Route For You dropdown Watchlist/Favorites choices through the same inline
  presentation as their in-page pills; profile-menu entries remain standalone.
- Add regression coverage for cache misses, request bounds, stale completion,
  cancellation, repeat-key boundaries, saved-list requests, and focus routing.

## Evidence

Production-backed emulator profiling before the change showed:

| Scenario | Janky frames | p95 | p99 | HTTP requests |
| --- | ---: | ---: | ---: | ---: |
| Horizontal cold | 18.34% | 53 ms | 700 ms | 45 |
| Horizontal warm | 5.09% | — | — | 0 |
| Second cold | 15.13% | 42 ms | 1000 ms | 46 |
| Vertical cold | 27.34% | 250 ms | 1000 ms | 47 |
| Vertical warm | 12.97% | 34 ms | 109 ms | 4 |

After the change, a combined cold-navigation run recorded 769 frames, 114
janky frames (14.82%), p50 10 ms, p90 24 ms, p95 46 ms, and p99 300 ms. The
captured run contained only five combined HTTP-completion/GC log matches.

Supply-chain policy checks, shared and TV-focused unit tests, Android shared
tests, and the minified TV release assembly passed. A final independent review
approved the branch after two correction rounds.

The device session ended before a complete Down/card/Up `For You` focus smoke
could be recorded. The focus bridge, repeat-key boundary, and saved-list
selection state are covered by behavioral unit tests, so external testing
should explicitly include those remote-control paths.

## Risk and rollback

Risk is concentrated in prefetch timing: slower networks may display metadata
slightly later because uncached enrichment no longer competes with active
focus navigation. Content remains available through the normal detail path.
The commits are separated by concern, so the focus bridge, repeat-key boundary,
saved-list routing, or either prefetch policy can be reverted independently if
external testing finds a regression.

The test APKs are debug-signed release builds and cannot replace an installed
production-signed build without a matching signer. They are intended for a
compatible tester installation only.
