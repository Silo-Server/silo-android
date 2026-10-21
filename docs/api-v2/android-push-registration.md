# Ordered Android push registration

The phone's existing foreground/reset registration and Firebase token callback use
`ordered_android_v1`. Registration capability describes storage support, not
provider availability or successful delivery. TV has no FCM registration caller.
Signing out of a server and removing a saved server remove this device's
registration there first, as described below.

The registrar captures durable login authority before requesting a Firebase
token. A later callback supersedes an older token lookup. A mutex serializes
installation changes, while an exclusive process lock protects the private,
non-backed-up file. The file stores the random 32-byte installation proof,
positive int64 generation, exact payload/verb and original server/login/profile/PIN
identity before dispatch. It stores no access or refresh tokens. Account/profile
changes keep the installation proof and advance the generation only for a new
intent after the preceding intent is acknowledged.

POST accepts only a matching string-generation receipt with registration and
server-device IDs and the requested mode. DELETE accepts only 204. Both are
single-attempt HTTP exchanges without authentication replay. An unchanged
acknowledged intent returns its stored receipt without re-enabling registration.
An uncertain or cancelled send retains its exact intent. A later invocation under
the same durable login/profile/PIN can retry that intent, including after process
restart. If the token or verb changed, at most one exact pending replay precedes
the new intent; allocation occurs only after acknowledgment. Replacement authority
cannot replay the original intent or publish its late result.

Removal allocates the next generation as a removal intent and records it, in the
same write, as a pending retirement (generation and time queued) before anything
is sent; a removal not yet confirmed keeps its generation. The retirement,
`POST /api/v2/notifications/push/devices/{device_id}/retire`
(`retirePushDevice`), carries only the installation key and generation headers,
with no session, so it still works after sign-out or removal has cleared the
login. It is sent first. Only a server that answers 404 or 405 gets the session
DELETE instead, at the same key and generation, while the registering login
still exists. A retirement without a final answer stays queued and is sent again
on app start, on every foreground and when an FCM message arrives, at most three
at a time with an in-memory back-off. 200 naming the generation confirms the
removal; 403, 409 and other refusals of the request drop the entry; 404 or 405
keeps it for 30 days from queueing; anything else keeps it for up to 90 days.
Signing in again, re-adding the server or undoing a sign-out that stopped
registers at a higher generation, which drops the pending retirement.

Conflicts and definite authority/validation refusals persist a stopped state.
There is no automatic generation rebase, secret reset, legacy fallback, counter
exhaustion recovery, or recovery UI. Corrupt storage or a missing file with an
initialization marker fails closed. Complete app-data loss cannot reconstruct
installation proof; the server's proof/bootstrap checks remain authoritative.
An unresolved intent from another login/profile/PIN blocks registration until its
original authority can resolve it. This conservative boundary does not promise
uninterrupted push delivery during account changes after uncertainty.

Activation requires the server migration and guarded writers on every API node;
a capability response cannot establish fleet-wide deployment safety. No real
installation enrollment is part of the tests. Provider requests already in
flight cannot be recalled, and a storage receipt does not guarantee immediate
delivery revocation.

Focused tests cover strict wire receipts/statuses and captured scope, durable
allocation before send, unchanged intents, exact uncertain replay, token rotation,
concurrent/cancelled sends, account replacement, late token lookup, storage failure,
proof preservation, and generation exhaustion. File tests cover round-trip
persistence, exclusive ownership, corruption and missing-proof refusal. They do
not exercise a live Firebase provider, device enrollment, actual auth-refresh
interceptor, operating-system process kill, or power-loss durability.
