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

The server records the caller's login session when it applies a new generation,
never on a replay, and capabilities advertise this as `binds_login_session`. A
registration made before that (written by an older app, or allocated while its
server did not advertise it) therefore gets one more generation for its unchanged
intent, once its server advertises it. This rebinding departs from the rules above
in three ways: an acknowledged, unchanged intent sends a request and advances the
generation, a new intent may supersede it while it is uncertain, because it only
repeats what the server already holds, and a definite refusal of it does not stop
it. It keeps its receipt until the new one arrives, so pushes stay readable, and
retries the exact generation until acknowledged.
A definite refusal of a first rebinding is the one refusal that rebases: the
server refuses before applying anything, and nothing earlier sent that generation,
so it still holds the generation the rebinding replaced, and the record returns to
that acknowledged generation and receipt. After a 403, 409 or
422 it is not rebound again; the next ordinary registration binds it. A 401 is
asked again later. A refused replay of an uncertain rebinding cannot return to the
generation before it, since an earlier send may have applied, so it stays uncertain
with its receipt: the server holds that generation or the one before, the same
registration either way. It is still read, removed above both at sign-out, and
superseded by any new intent, and until then it is retried like any uncertain intent.
A saved server that is not active is asked at most daily whether it can be rebound;
a rebinding already uncertain retries like any other uncertain intent. A removal or
a login on its way out is never sent again this way.

Removal allocates the next generation as a removal intent and records it, in the
same write, as a pending retirement (generation and time queued) before anything
is sent; a removal not yet confirmed keeps its generation. The retirement,
`POST /api/v2/notifications/push/devices/{device_id}/retire`
(`retirePushDevice`), carries only the installation key and generation headers,
with no session, so it still works after sign-out or removal has cleared the
login. It is sent first and given at most 2 of the removal's 8 seconds. A server
that answers 404 or 405, or does not answer in time (no answer, 429, 5xx), also
gets the session DELETE, at the same key and generation, while the registering
login still exists. A retirement without a final answer stays queued and is sent again
on app start, on every foreground and when an FCM message arrives, at most three
at a time with an in-memory back-off; an attempt that runs out of time counts as
a failure. A removal that never queued its retirement, because it ran out of
time waiting on that server or an older version made it, is queued then, once
its login is gone. 200 naming the generation confirms the
removal; 403, 409 and other refusals of the request drop the entry; 404 or 405
keeps it for 30 days from queueing; anything else keeps it for up to 90 days.
Signing in again, re-adding the server or undoing a sign-out that stopped
registers at a higher generation, which drops the pending retirement.

Conflicts and definite authority/validation refusals persist a stopped state,
except for a rebinding as described above. There is no other automatic generation
rebase, secret reset, legacy fallback, counter
exhaustion recovery, or recovery UI. Corrupt storage or a missing file with an
initialization marker fails closed. Complete app-data loss cannot reconstruct
installation proof; the server's proof/bootstrap checks remain authoritative.
An unresolved intent from another login/profile/PIN, other than an uncertain
rebinding, blocks registration until its original authority can resolve it. This
conservative boundary does not promise uninterrupted push delivery during account
changes after uncertainty.

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
