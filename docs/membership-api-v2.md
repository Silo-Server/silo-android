# Android membership v2 adapter

`MembershipV2Api` implements favorite and watchlist GET, PUT, and DELETE operations.
Existing consumers and outbox dispatch remain unchanged pending a separate adoption
checkpoint. These profile-scoped operations use the shared API contract gate; the
server exposes no separate membership capability endpoint.

GET requires the typed `item_id` and `added_at` entry. Only HTTP 404 becomes an
absent membership; authorization, server, and malformed-response failures remain
errors. The response item must match the requested item. Viewer changes during a
read prevent publication.

PUT and DELETE require the contract's empty 204 response. Each request opts into
single-attempt auth handling after the normal origin checks, so a 401 returns to
the caller without token-refresh replay. Other requests retain their existing
refresh behavior. A captured authority that is already stale is refused before
sending.

A confirmed acknowledgement carries the requested item, desired membership, and
captured authority. It remains available after an active-viewer change so a caller
can acknowledge its exact old command. It does not authorize publishing that result
into the new viewer's UI. Cancellation or an uncertain network outcome is not an
acknowledgement and must not be treated as safe to replay by a generic outbox rule.
Consumer adoption must retain command identity through acknowledgement and protect
newer commands and their optimistic state.

Focused tests cover both read operations, all four empty-body writes, the contract
gate, captured-scope refusal and acknowledgement, real auth-plugin 401 request
counts with an unchanged default read control, and foreign-origin credential
stripping. No consumer activation or outbox-policy change is included here.
