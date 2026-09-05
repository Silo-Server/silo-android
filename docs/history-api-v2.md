# Android history v2 adapter

`HistoryV2Api` reads one bounded page from `GET /api/v2/history`. Consumer adoption
is a separate checkpoint; the existing history screen still uses its previous
transport until that step lands.

The required `items` and `page` envelope has no total, offset, or window cursor.
Each entry retains its catalog card and required watch metadata, including the
watched episode ID when the displayed card represents a series. Unknown watch
source strings are retained.

A process-local continuation captures the viewer identity, page size, artwork
size, seen cursors, and seen displayed content IDs. Empty or short pages continue
when the server supplies a next cursor. Duplicate cards are omitted while the
server cursor still advances; the first occurrence retains its watch metadata.
Repeated or malformed cursors fail, and an expired cursor requires an explicit
first-page request. A changed viewer or request cannot reuse a continuation.
Cancellation and a viewer change during the read prevent publication.

Production adoption must supply the actual API gate and token manager and guard
screen publication by its load generation. Tests cover empty and duplicate pages,
watch metadata, malformed envelopes, changed request/viewer scope, repeated and
expired cursors, explicit restart, and cancellation. Favorites/watchlist catalog
consumers, dormant standalone wrappers, membership mutations, and watch transport
are outside this adapter.
