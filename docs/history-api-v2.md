# Android history v2 adapter

`HistoryV2Api` reads one bounded page from `GET /api/v2/history`. The existing
PersonalDataApi and repository route history reads through it. Phone and TV use
the dedicated shared HistoryViewModel, with production gate, token manager, and
identity-transition dependencies.

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

The ViewModel captures its load generation and identity generation before each
read, cancels superseded loads, and rejects stale publication. Identity transitions
clear old cards before loading the new profile after the transition completes.
Watch metadata stays associated with the displayed card, and the shared visual
state represents history’s unavailable total as null. Empty-page continuation is
driven by the existing grids; a cursor error retains rows and requires reload.

Tests cover the actual repository/ViewModel path through empty and duplicate pages,
watch metadata, malformed envelopes, changed request/viewer scope, repeated and
expired cursors, explicit restart, and cancellation. Favorites/watchlist catalog
consumers, dormant standalone wrappers, membership mutations, and watch transport
are outside this adapter.

Phone and TV debug APK builds pass. No live device or backend validation is
claimed by the build checks.
