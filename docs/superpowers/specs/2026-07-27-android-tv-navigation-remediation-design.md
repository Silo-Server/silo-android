# Android TV Navigation Remediation Design

## Purpose

Fix two Android TV regressions reported against v1.0.0 and reproduced on the
current client against `lib.strm.cafe`:

1. The For You filter band cannot transfer D-pad focus into recommendation
   rows.
2. Home navigation is sluggish during the first traversal after process start,
   then becomes responsive after the same content is warm.

The change is Android TV focused. Shared startup hydration may change where it
directly removes duplicate Android phone/TV work, but server APIs, server
configuration, content ordering, recommendation generation, and playback are
out of scope.

## Verified Causes

### For You focus

`TvRecommendationsScreen` initially focuses the Watchlist pill but does not
provide a Down target or directional handler for any filter pill. Its
`TvMediaRow` instances also receive neither a row-container focus requester nor
a first-card focus requester. Compose's geometric search therefore leaves focus
on Watchlist, while Watchlist and Favorites work because their catalog grids
provide a different focus topology.

### Cold Home navigation

Several independent eager paths overlap after process start:

- `warmAuthenticatedStartup` fetches the aggregate Home response and then
  unconditionally fetches every section again with unbounded concurrency.
- `HomeViewModel` independently fetches the aggregate Home response while the
  startup warmup is running.
- `TvSkylineSectionFeed` starts hero artwork and full-detail requests for as
  many as sixteen cards on page entry.
- Raw focus movement starts neighbor artwork and network-first detail requests
  before the existing 150 ms rested-focus decision.

Servers with more libraries and rows amplify the first two costs, but server
size is not itself an error. The client must remain responsive for a
production-sized Home response.

Archived PR 108 commits `49a70045` and `65c4b316` document earlier intent to
remove the page-entry fan-out and hydrate only unresolved Home sections with a
four-request bound. They are historical references, not patches to apply
blindly: current main has newer Home response handling and must retain it.

## Design

### 1. Explicit For You focus bridge

`TvRecommendationsScreen` will own stable focus requesters for:

- the filter pill that should receive Up from recommendation content;
- the first nonempty recommendation row container; and
- the first card in that row.

Every filter pill routes Down according to the visible content:

- For You: request the first row container, wait one frame for the row
  `focusRestorer` boundary, then request its first card.
- Watchlist and Favorites: preserve their existing catalog-grid behavior.
- Loading, error, and empty states: do not target an absent row; their existing
  actionable control remains reachable.

The first For You row routes Up back to the selected filter pill. Initial entry
continues to focus Watchlist, preserving current product behavior.

The handoff will reuse the established `TvMediaRow` requester contract rather
than introducing a second focus-navigation architecture.

### 2. Deliberate Home-to-menu focus boundary

Rapid or held Up input must traverse Home rows one at a time and stop on the
first content row. Reaching that row as part of the same repeated-key sequence
must not immediately move focus into the top menu. A new Up press after the
remote key has been released may enter the selected top-menu item.

The Skyline row band will continue to own off-screen row relocation. It will
serialize that relocation so overlapping key-repeat events cannot start
competing scroll jobs or consume more row transitions than completed focus
moves. The shell remains the only owner of the final content-to-menu handoff.

### 3. Unify For You saved-list presentation

Watchlist and Favorites chosen from the For You top-menu selector will open the
existing `TvRecommendationsScreen` with the matching saved-list pill selected.
They will therefore use the same filter band, inline grid, spacing, focus
behavior, and Back destination as choosing those pills after entering For You.

Watchlist and Favorites chosen from the profile menu remain standalone utility
pages. This preserves their established account-navigation role and avoids
turning every saved-list deep link into a For You route. No repository, server,
or personal-data behavior changes; both presentations continue to use the
existing `WatchlistViewModel` and `FavoritesViewModel`.

### 4. Resolve Home once, hydrate only missing sections

Extract a small, platform-neutral Home hydration operation that:

- accepts aggregate `ResolvedSection` values;
- preserves sections whose items are already inline;
- fetches only sections that are empty while reporting a nonzero total;
- accepts either nested response items or top-level response items;
- limits fallback requests to four concurrently;
- reports whether the snapshot was fully resolved so a partial result cannot
  replace a good cache.

`HomeViewModel` and startup warmup will share this operation. Startup warmup
will no longer refetch every inline section.

The activity warmup remains best-effort and non-blocking. This change does not
make splash dismissal wait for Home.

### 5. Remove page-entry detail fan-out

`TvSkylineSectionFeed` will not eagerly fetch full detail or hero-sized artwork
for the first sixteen cards merely because rows entered composition. Startup
artwork warmup already has a bounded, paint-order budget; the Skyline will seed
the first marquee from aggregate section data and enrich around actual user
focus.

Neighbor enrichment remains speculative but will be driven by the rested
focused identity rather than every intermediate D-pad position. It will:

- operate on a small neighbor window;
- preserve request deduplication for the page lifetime;
- use cached item detail before network;
- cap network detail concurrency;
- cancel obsolete work when rested focus changes.

Opening an item-detail screen keeps its existing network-first freshness
semantics. Cache-first behavior applies only to speculative marquee enrichment.

### 6. Cache and dispatcher boundaries

Room remains the profile/server-scoped source for offline Home and item-detail
snapshots. The fix will not alter cache schema or migration state.

Home JSON encoding and Room access continue on the existing IO-owned startup
scope or repository suspending boundary. If tests show serialization executing
on the main dispatcher, the repository will explicitly move serialization to a
background dispatcher; otherwise no dispatcher abstraction will be added.

## Error and Lifecycle Behavior

- Failed speculative image/detail requests remain non-fatal and do not block
  focus.
- A failed fallback section fetch leaves the prior complete Home cache intact.
- Switching server or profile continues to select the corresponding scoped
  Room data.
- Leaving the Home composition cancels its speculative jobs.
- A process restart may refresh content, but it must not re-download cached
  detail solely for prefetch.
- No tokens, origins, diagnostics, or production settings change.

## Verification

Behavioral tests will cover:

- Down from each For You filter pill, Up return, repeated movement, and
  loading/error/empty states;
- rapid and held Up sequences stopping on Home's first content row, followed
  by a fresh Up press entering the selected top-menu item;
- For You selector Watchlist/Favorites opening the same inline presentation
  and selected pill as in-page selection, while profile-menu routes remain
  standalone;
- inline Home sections causing zero per-section fallback requests;
- missing sections being hydrated correctly with at most four concurrent
  requests;
- partial hydration preserving cache safety;
- Skyline page entry producing no full-detail burst;
- rapid focus movement starting work only for the rested identity;
- cached detail avoiding network and bounded fallback when cache misses.

Verification will include focused shared and Android TV unit tests, Android TV
debug/release compilation, and a controlled Shield run against
`lib.strm.cafe`. The device gate will compare cold-process and immediate warm
traversals using frame-jank percentiles and sanitized request counts. Success
requires:

- For You Down enters its first visible recommendation card reliably;
- no page-entry sixteen-detail burst;
- no per-section N+1 when aggregate sections are inline;
- materially lower cold first-traversal request count and jank without
  worsening warm traversal;
- no crash, ANR, authentication change, or server mutation.

## Alternatives Rejected

- **Server-side global row caps:** reduces content and masks client-side
  duplicate work.
- **Longer splash or input suppression:** hides latency instead of removing it.
- **Blind cherry-pick of archived commits:** risks discarding newer response
  compatibility and focus-restoration behavior.
- **Disabling all prefetch:** avoids the burst but makes every settled focus pay
  full network and image latency.
- **Timeout or animation tuning:** does not address the measured cold request
  fan-out.
- **Making all Watchlist/Favorites routes inline:** would change profile-menu
  and deep-link semantics to solve a mismatch limited to the For You selector.
- **Debouncing all Up input:** would make ordinary row traversal feel laggy;
  only the asynchronous row relocation and content/menu boundary need
  sequencing.

## Scope Boundaries

This remediation does not change recommendation ranking, Home row composition,
server endpoints, production configuration, phone UI navigation, playback,
database schema, or authentication. It does not attempt a general Compose focus
framework or a complete image-loading redesign.
