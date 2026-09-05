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

Item details, season/episode/version reads, people-detail and watch transports, and
the separate v1 history transport remain outside this listing migration. No
playback or administrative UI is added.

Validation includes shared transport/query tests, viewer-change refusal, typed
library group routing, phone letter-index ViewModel tests, TV library destination
tests, and a hidden-book-page/expired-cursor/reload regression. Phone and TV debug
APK builds are the platform gate. Live backend/device validation remains pending
a reserved test device and an integrated backend fixture.
