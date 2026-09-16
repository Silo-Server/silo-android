# Android membership v2 adapter

`MembershipV2Api` implements favorite and watchlist GET, PUT, and DELETE operations.
It is the only membership transport: `PersonalDataApi` carries no favorite or
watchlist methods, and the outbox dispatches through `MembershipV2Api` directly.
These profile-scoped operations use the shared API contract gate; the server
exposes no separate membership capability endpoint.

GET requires the typed `item_id` and `added_at` entry. Only HTTP 404 becomes an
absent membership; authorization, server, and malformed-response failures remain
errors. The response item must match the requested item. Viewer changes during a
read prevent publication.

PUT and DELETE require the contract's empty 204 response. Each request opts into
single-attempt auth handling after the normal origin checks, so a 401 returns to
the caller without token-refresh replay. Other requests retain their existing
refresh behavior. A captured authority that is already stale is refused before
sending.

A confirmed acknowledgement is the 204 itself; the caller already knows the
item, desired membership, and captured authority it sent. It remains available
after an active-viewer change so a caller can acknowledge its exact old command.
It does not authorize publishing that result into the new viewer's UI. Cancellation or an uncertain network outcome is not an
acknowledgement and must not be treated as safe to replay by a generic outbox rule.
Consumer adoption must retain command identity through acknowledgement and protect
newer commands and their optimistic state.

Focused tests cover both read operations, all four empty-body writes, the contract
gate, captured-scope refusal and acknowledgement, real auth-plugin 401 request
counts with an unchanged default read control, and foreign-origin credential
stripping. Phone, TV and shared consumers use the typed membership port described below.

## Outbox foundation

Room schema 9 adds nullable membership authority, claim and process-owner fields to
`dirty_operations`. Existing rows and retry counters are preserved. Membership
commands opt into separate ready, sending, reconcile and paused states; legacy
SyncEngine pending/in-flight queries do not select or recover them.

`RoomMembershipPort` owns one guarded runtime shared by inline producers and the background worker. Both inline and background senders must call the
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
handling and validates it before constructing an authority. Legacy favorite rows remain quarantined without adopting the new login authority.


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

Phone and TV each bind one lazy `RoomMembershipPort` singleton. Every entry waits behind
its recovery mutex; readiness is published only after successful, non-cancelled
Room recovery. Failed or cancelled initialization can retry. Its process UUID is
created once per singleton, never per worker/drain. Application startup ordering
is therefore not relied upon to exclude workers racing recovery. Existing legacy queue rows retain their original authority metadata; they are never converted into current-login commands.

## Coordinated storage and consumer cutover

Production schema 10 migrates each legacy favorite row to quarantine and stores
its original state separately. Payload, ID, timestamps, attempts, errors and
authority fields remain intact. Generic cleanup, recovery, coalescing, count,
FIFO and terminal projection rollback exclude quarantine. No current login is
assigned to those rows. An insertion trigger rejects obsolete favorite producers
and rolls back their entire transaction. All active producers use the typed port;
the old repository favorite write rejects calls, including its no-scheduler mode.

Admission validates captured durable authority, then holds the identity barrier
through one Room command/projection transaction. Projections are keyed by
server/login/profile, item and list kind, with an exact command ID owner. Resolving
an old command cannot change a newer projection, including identical desired
values. Exact 204 acknowledgements and GET reconciliation update projection
disposition in the same transaction that resolves the claimed command.

The worker makes one bounded READY-only pass through the same runtime as inline
senders. Uncertain, paused and quarantined commands do not drive worker retries.
Room invalidations notify the visible recovery surface about background results.
A recovered sending claim needs reconciliation and is never ready to resend.

Shared, phone and TV consumers capture per-item, per-field intents before launching
work. A generation change clears those witnesses. Completion requires the same
intent and generation; authoritative membership reads reject intervening intents.
Admission retains the committed command witness even when its coroutine is cancelled.
The last confirmed field value remains separate from a pending opposite intent.
Home and personal-list reads capture confirmed witnesses before requesting data;
accepted response rows supersede only those captured witnesses. An earlier read
cannot erase a later acknowledgement. Home requests with confirmed witnesses do
not join a request already in flight before the acknowledgement.
Successful membership GETs capture both intent and display-baseline witnesses.
They update that field's shared display baseline only if neither witness changed
while the read was in flight. Detail screens, card menus and episode rows thus
observe newer server truth without replaying or rewriting the pending command.
Confirmed fields overlay older cards independently. A failed favorite cannot
roll back another field or replace a newer home snapshot. Personal-list removals
retain their row, total and continuation until acknowledged or reconciled.

The recovery banner restores a bounded set of current-authority commands and
provides explicit GET status checks. A mismatch pauses the command; the user can
review the item and make a new explicit choice. Legacy quarantine receives a
separate generic notice directing the user to current Favorites. Dismissing that
notice hides only the notice, never its retained rows. Neither recovery surface
requests TV focus or replaces the existing list content.
