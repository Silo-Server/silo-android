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

## Outbox foundation

Room schema 9 adds nullable membership authority, claim and process-owner fields to
`dirty_operations`. Existing rows and retry counters are preserved. Membership
commands opt into separate ready, sending, reconcile and paused states; legacy
SyncEngine pending/in-flight queries do not select or recover them.

`MembershipOutbox` is a foundation for subsequent consumer activation. Its guarded runtime is registered lazily in production DI, with no active
producers or dispatch calls. Both inline and background senders must call the
same `send` method; neither may send first and claim afterwards. Each enqueue gets
a new database row and UUID even when its payload equals an older command. Pending
intent coalesces; a sending command remains immutable and blocks another send for
the same key. Only an exact row, claim and authority match can consume a confirmed
204. A newer coalesced command survives the old acknowledgement. Acknowledgement
publication remains subject to the consumer's identity/generation checks at its
actual UI update; the returned publication hint is not a synchronization lock.

Cancellation or any unconfirmed response transitions the claimed command to
reconciliation, never to automatic mutation retry. Explicit reconciliation issues
a captured-authority GET. Observing the desired state resolves that command as
reconciled, without claiming that mutation side effects were replayed. A mismatch
pauses it; another explicit reconciliation or a new user command can resolve the
situation. Read failures retain the unresolved command.

Startup must construct one process-wide owner identity and recover abandoned
sending claims before starting any membership senders. Claims owned by a previous
process become reconciliation work, including claims abandoned before transport
started. There is no lease timeout that could steal an active request. This owner
protocol requires the application's existing single-process execution model.

Activation uses the durable login authority binding described below. Server/profile
identifiers and AuthScopeSnapshot generation counters are insufficient: counters
reset across process restarts. The storage interface therefore requires this key
explicitly and does not persist credentials or infer authority from those counters.
The production binding persists login identity alongside credential lifecycle
handling and validates it before constructing an authority. Existing
legacy favorite rows, UI producers, watchlist producers and SyncEngine dispatch
remain unchanged until that binding and consumer adoption are reviewed.


## Durable authority and runtime admission

`EncryptedTokenManagerImpl` implements `DurableLoginAuthorityProvider`. Explicit
account replacement commits a fresh login UUID with the credentials and profile
in the existing encrypted-preferences transaction. Sign-out removes it; server
removal sweeps the server namespace. Refresh and profile/server switching preserve
it. Temporary credentials cannot supply durable membership authority.

An existing authenticated installation bootstraps its missing UUID under the token
write and scope locks with a checked synchronous commit. A failed commit can
change SharedPreferences memory, so the provider tracks an unconfirmed bootstrap
and retries persistence before exposing authority. Failed credential replacement
or sign-out blocks authority until a successful credential transaction resolves
that failure. No old Room command is assigned the bootstrapped identity.

The provider returns the persisted UUID with the exact runtime request snapshot.
`MembershipRuntime` derives an unambiguous JSON tuple of server, login and profile
for the command authority, and validates both persisted and runtime identity
before admission. This prevents an old callback from adopting a later login even
when its server/profile identifiers or process-local counters happen to match.

Phone and TV bind one lazy `MembershipRuntime` singleton. Every entry waits behind
its recovery mutex; readiness is published only after successful, non-cancelled
Room recovery. Failed or cancelled initialization can retry. Its process UUID is
created once per singleton, never per worker/drain. Application startup ordering
is therefore not relied upon to exclude workers racing recovery. Producers,
legacy command conversion and SyncEngine dispatch remain inactive pending their
own review; existing legacy queue rows retain their original authority metadata.
