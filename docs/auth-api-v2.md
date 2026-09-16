# Native authentication and device sign-in

The existing phone and TV authentication paths use v2 login, setup, signup,
signup status, logout and refresh. Setup status and current account use
`/api/v2/system/setup` and `/api/v2/account/me`. Device login uses v2 start, poll, lookup, capability,
approve, deny and profile-scoped handoff approval. Media credential refresh uses
the same v2 refresh route while preserving its captured-origin credential guards.

Login accepts200; setup, signup and device start accept201; device polling and
decisions accept200; logout accepts204. Successful responses with unexpected
statuses fail instead of being treated as acknowledgements. Device polling maps
its nested `tokens` object into the existing domain response. Account, profile and
impersonator IDs stay strings without numeric coercion. Device capability requires
revision/state and enables remote handoff only when state is available.

Public auth exchanges omit current bearer/profile credentials. Candidate-server
calls retain explicit URLs. Optional request members are omitted when absent by
the existing JSON configuration. Login, setup, signup, device start and collecting
polls opt out of auth-plugin replay. A successful pending poll schedules the next
protocol poll; a failed or ambiguous poll requires an explicit restart, because
its response may have contained the only issued token pair. Phone and TV prevent
repeated auth submissions while the first is pending.

Before an asynchronous session exchange, callers capture the intended server URL
and an account identity generation under the identity transition barrier. Account
replacement compares that generation while holding the same barrier, before
publishing a transition or committing credentials. A changed server or newer
login rejects the old completion. Durable login UUID creation remains part of the
accepted atomic account transaction. Refresh retains its existing scope checks
and does not create a new durable login identity.

This expectation is carried by ordinary repository login/setup/signup, TV
password/QR sign-in, companion pairing and existing invitation acceptance.
Post-commit publication checks reject results after another identity transition.
Custom TokenManager implementations must implement atomic expectation capture
and replacement; the default interface refuses a guarded installation rather
than falling back to check-then-write.

Invitation transport remains on its existing v1 domain. Browser OAuth initiation
and callback and plugin launch/proxy are unchanged. Provider listing, OAuth
completion, password management and session-management UI have no active Android
consumer in this slice and are not introduced. Membership runtime activation is
separate; none of its producers or dispatch paths are enabled here.

## Email invitation claims

The phone claim flow uses public v2 invitation capabilities, lookup and acceptance.
It checks global support and the token's `acceptance_available` separately. Only a
lookup 404 marks the token invalid; server failures remain retryable lookup errors.
All requests pin the invitation origin and omit existing credentials. Acceptance
is a single-attempt POST requiring 201 and the typed accepted outcome with string
account IDs. Invalid or inconsistent token wrappers are failures, not sessions.

`sign_in_required` is committed account creation. The screen offers ordinary
sign-in with the returned username, without installing credentials, switching the
server, or accepting the invitation again. Unconfirmed acceptance likewise offers
sign-in recovery and suppresses further submission for that route. Password
validation respects the eight-character minimum and 72-byte UTF-8 maximum.

A signed-in result uses the existing atomic account replacement. Its captured
expectation includes a synchronous route/attempt predicate evaluated inside both
token stores' replacement lock. A replaced route cannot install a late session,
even if cancellation cannot stop the pending completion. Duplicate submissions
are excluded before launching a coroutine. Public invitation claiming remains
phone-only; TV exposes no invitation claim screen.
