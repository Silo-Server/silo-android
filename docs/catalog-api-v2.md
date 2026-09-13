# Android catalog v2 adoption

Phone and TV catalog listing, search, person-credit listing, favorites/watchlist,
regular library collection tabs and items, facets, and audiobook groups use the
shared `CatalogV2Api` adapter. Production dependency injection supplies the contract
gate and token manager. The earlier personal collection and request migrations are
preserved.

Simple queries use GET `/api/v2/catalog` with a signed sort field. Structured
filters use POST `/api/v2/catalog/query` with JSON rule groups and scalar or array
values. A continuation retains its operation, complete query, artwork size, page
size, and viewer identity. Changed queries or viewers fail before sending; expired
or malformed cursors require an explicit first-page reload. No request invents an
offset or falls back to another search provider. Pages are limited to 100 items.

Strict response DTOs retain `page`, `total_exact`, `window_cursor`, effective sort,
and search diagnostics. The window cursor is an opaque server seed, not a timestamp
or page offset. No native window-jump UI is introduced here. Search capabilities
expose provider limits and fixed session lifetime without interpreting the ranked
window size as a global match count.

Facet reads use `skip_technical` and decode nested technical values. Author,
narrator, and series pickers search the server by prefix, retain the facet scope,
and ask the viewer to refine when more than 100 values match. They do not treat
the initial 1000-value vocabulary as complete. Audiobook
aggregate reads decode the `items` envelope. Library collection tabs retain their
full collection list, group kinds, and ungrouped section in typed DTOs; Silo IDs
remain strings at the wire boundary.

Focused transport tests cover signed sorting, cursor reuse and changed-query
refusal, structured query bodies, repeated-cursor refusal, and nested facets.

Views retain opaque continuation with the displayed query and clear it through an
explicit reload. Search and library generation checks prevent old query results
from publishing new continuation. Phone browse cancels superseded loads. TV
collection paging advances server cursors across reading-only pages before
presenting visible cards. Paging errors preserve the existing data and offer
explicit reload; they do not silently substitute a fresh first page. Search UI
labels estimated counts instead of presenting them as exact totals.

Continuations are not serialized into the offline first-page cache. A cache
fallback disables further paging until the viewer reloads online. It cannot seed
a new query with an old server cursor.

The watch transport, person refresh, and the
history transport are separate from this catalog migration. No playback or
administrative UI is added.

Validation includes shared transport/query tests, viewer-change refusal, typed
library group routing, phone letter-index ViewModel tests, TV library destination
tests, and a hidden-book-page/expired-cursor/reload regression. Phone and TV debug
APK builds are the platform gate. No live backend or device validation is claimed
by these checks.

## Detail read adapter

The shared adapter has separate wire projections for item detail, seasons, season
episodes, and people reads. Existing phone/TV detail and person consumers use
these adapters through CatalogRepository. Their existing
domain IDs are numeric, so projections require quoted decimal IDs and reject
out-of-range values before conversion. File IDs must fit a positive `Int`; person
IDs must fit a positive `Long`. Unsupported IDs fail the read rather than being
truncated or silently omitted.

Seasons, season episodes, and people search require an `items` envelope. Their
current contract accepts no cursor and omits `page`; an absent page is valid.
An unexpected continuation is rejected rather than presenting a partial hierarchy
as complete. People search remains a bounded name lookup (default 20, maximum
100), not an exhaustive person directory.

These reads use the existing gate, captured viewer scope, and cancellation checks.
Catalog markers retain `start` and `end`; watch transport is a separate contract
and is not changed here. Item file versions read the `items` envelope of
`/api/v2/catalog/items/{id}/versions` through the same checked projection.
Person refresh and standalone history are outside this read adapter. History adoption is
documented in `history-api-v2.md`.

The season and episode reads are shared by both players' existing next-episode
resolution. Tests cover the real repository envelopes feeding the shared resolver,
within-season advancement, rollover past specials, and failed or partial current
seasons remaining errors. Player orchestration is unchanged. Detail cache tests
continue to cover warm-read coalescing, offline fallback, and viewer changes.

Season download subscriptions use the item-episodes read through the same checked
v2 episode projection. This operation accepts a season item ID (including supported
synthetic season IDs), applies series visibility checks, and returns a finite
`items` envelope. Unknown or inaccessible seasons remain errors. Factory-path
tests cover ordered episode/file IDs, watched-item filtering, rejected partial
responses, viewer changes during reads, and cancellation without enqueueing.
