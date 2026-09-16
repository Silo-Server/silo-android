# Ordered Android push registration

The phone's existing foreground/reset registration and Firebase token callback use
`ordered_android_v1`. Registration capability describes storage support, not
provider availability or successful delivery. TV has no FCM registration caller.
The registrar's existing removal method uses the ordered DELETE contract, but no
production invocation of that method or new removal UI is claimed.

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
