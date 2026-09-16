# Android collection API migration

Personal collection definitions, groups, ordering, and membership mutations use
`/api/v2` through the existing API contract gate and problem decoder. Collection
lists decode the required `items` envelope and retain `groups`. IDs remain strings.

The shared API and repository expose:

- List and create collections; canonical collection reads, PATCH updates, moves,
  and DELETE.
- Create groups; canonical group reads, PATCH updates, and DELETE.
- Canonical collection, group, and item order reads and guarded PUT writes.
- Membership PUT with a JSON `position` value and membership DELETE.
- Collection capabilities, including store-dependent group and item-order support.

Editing requires a `CollectionEditor` returned by a canonical read. It retains the
strong ETag and captured authentication scope. Callers pass that same snapshot to
writes; list refreshes do not replace it. A changed viewer fails before sending.
A 412 returns a conflict message and preserves the original snapshot. Reload is an
explicit caller action. Order snapshots with `has_more` cannot be submitted as a
complete order. Moving to Ungrouped sends an explicit JSON null. PATCH no longer
sends the create-only `collection_type` field.

The phone and TV collection screens currently render browsing grids and do not
wire up their ViewModels' deletion handlers. Those existing handlers now fetch a
canonical snapshot before opening confirmation and retain confirmation on failure.
The phone removal handler removes the displayed item only after success. No new
management controls were added. Future controls must offer explicit reload after a
conflict and gate optional management actions with collection capabilities.

Hydrated personal collection browsing uses `/api/v2/catalog` with
`source=user_collection`, the collection ID, a bounded limit, and opaque cursor
continuation. It sends no offset or sort override. Each continuation carries its
collection, page size, and viewer scope; a changed viewer fails before sending.
Invalid continuation requires explicit reload and never silently starts over or
appends a first page. Phone and TV cancel superseded page loads and retain existing
items on failure while showing the reload action. TV filtering does not affect
cursor advancement. Regular library-collection browsing now uses the shared catalog v2 adapter
described in `catalog-api-v2.md`. The v2 membership GET returns join records rather than catalog cards.

No mutation adds automatic replay or fallback to v1. The existing authentication
refresh behavior is unchanged. Successful empty 204 responses are accepted by the
shared v2 decoder as Unit.

Validation uses `CollectionsV2Test` and `RequestsV2Test`, plus Kotlin compilation
for phone and TV. The collection tests cover the items envelope, missing-envelope
failure, canonical ETags and conflict preservation, explicit-null moves, membership
position JSON and empty success, missing validators, oversized order refusal, and
lost-viewer rejection, opaque browse continuation, and invalid-cursor refusal without
automatic restart. Live server/device interaction is not verified by these
mock-transport tests.
