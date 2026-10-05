# Native authentication and device sign-in

The existing phone and TV authentication paths use v2 login, setup, signup,
signup status, logout and refresh. Setup status and current account use
`/api/v2/system/setup` and `/api/v2/account/me`. Device login uses v2 start, poll, cancel, lookup,
capability, approve, deny and profile-scoped handoff approval. Media credential refresh uses
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
protocol poll; an approval without usable tokens requires an explicit restart
with a new code, because the server issues the token pair once. Phone and TV
prevent repeated auth submissions while the first is pending.

## TV device sign-in

`DeviceSignInMachine` (shared) drives the TV sign-in screen's code; the
setup-mode LAN receiver uses the single-attempt loop in
`DeviceLoginRepository`. Both read every poll answer through
`DeviceLoginPoller`, so they follow the same rules:

- Poll at once after start, then at `interval` / `poll_after`. Network errors,
  5xx and 429 back off from the interval to 30 s. A 404 or another 4xx on poll
  means the server no longer has the request.
- A local deadline starts at `expires_in`. A pending poll answer carries the
  request's current `expires_at`, which an approver's lookup extends; the
  deadline moves to it, converted to the device's clock through the start
  answer's own `expires_at`/`expires_in` pair. Waits are clamped to the
  deadline, so the last call before a code is given up is a poll.
- The sign-in screen replaces a code only after a poll: when the server says
  it is expired, consumed or canceled, or when a poll at the deadline still
  finds it pending, in which case the code is withdrawn with `cancel` first.
  A capability read that failed earlier is retried then; the code is withdrawn
  unless the capability says `cancel` is false, so a read that fails again
  still sends the cancel (a server without it answers 404). It pauses after
  an hour without the person doing anything, reports "can't reach" after 10 s
  of failed starts or 30 s of failed polls, and "too many requests" after a
  minute of 429s, while still retrying. A 404
  or 501 start, or a capability `state` other than `available`, means the
  server has no device sign-in: the password form.
- `ON_STOP` ends the wait between polls; a poll already in flight finishes and
  an approval being saved completes, because the server hands out the tokens
  once. `ON_START` polls a live code at once. Leaving the screen, retrying,
  changing server or signing in with a password withdraws the code with
  `cancel` (also when the capability couldn't be read; a server without
  `cancel` answers 404). A password save that fails leaves the code working.
  "Continue on your phone" shows when a poll reports `opened`, which only
  servers that advertise `opened_signal` send.
- The code is shown grouped 4+4 with `<host>/activate` and a QR of
  `verification_uri_complete`. Match words are never shown on the TV. The
  phone's LAN confirmation adds an "Older TV apps show ..." line with them,
  like the web `/activate` card, until TV apps that show user codes have
  shipped.

## Server identity and LAN pairing

Saved servers record the deployment identity verified at their URL
(`ServerEntry.verifiedServerId`, from `GET /api/v2/system/identity`).
`silo://device?server=<server_id>&url=<base>&code=<code>` links and signed-out
TVs are matched to saved servers by that identity, never by URL spelling; when
no identity can be verified the link's origin is matched instead. A link
without `server` (the web page omits it when the identity is unknown) is
scoped by `url`'s origin, the way an HTTPS device link is.

A link's code is approved on the saved server it names, through that server's
own profileless account scope, without switching the phone's active server
(silo-apple parity). When the phone isn't signed in there it asks to sign in
first rather than trying another server. Only `token` links, which the active
server's lookup alone accepts, still offer the switch. A link naming a server
the phone hasn't saved offers to add it, with the link's `url` prefilled.

The approver renews its access token before looking a code up and before
approving or denying: the approval's account read and decision calls are
marked `freshSiloAuth()`, so `SiloAuthPlugin` refreshes that server's scope
first when its token is expiring, under the same lock as the 401 refresh.
On a saved server other than the active one, the card names the account read
through that server's scope; when that read fails it says it couldn't confirm
the account (or that the provider can't be reached, when that was the
reason), offers a retry, and keeps approving disabled.
"Not you?" signs out of that server's account and returns to sign-in, keeping
the pending code; on a server other than the active one it makes that server
active first. As on silo-apple, it reads "Not you? Switch account" when the
next sign-in lets the person choose (a provider that takes `select_account`,
or only the password form) and "Not you? Sign out" otherwise, including when
the server's sign-in options couldn't be read. Where a provider takes
`select_account` and the server lists exactly one provider, the login screen
that follows starts that provider's sign-in by itself, once
(`NativeSignInCoordinator.requestAccountChoice(autoStart = true)`, kept in
memory only and handed back if the screen goes away before the browser
opens). "Sign in" after a 401 does the same without
signing out: it signs in on the server the code was looked up on.

The LAN pairing protocol matches silo-apple frame for frame
(`PairingProtocolGoldenFramesTest`). A TV on its sign-in screen advertises
`_silopair._tcp` with TXT `st=login` and `srv=<server_id>`; a phone offers it
only when it holds a signed-in server with that verified identity, skips the
server chooser and pushes only that server. That TV accepts only a push whose
`serverIdentity` equals its `srv` and answers with the code its sign-in screen
already shows; the screen polls and saves the session, so there is only one
request. The TV advertises only while its sign-in screen is visible.
Advertising `st=login` sits behind a rollout gate shared with silo-apple
(`PairingProtocol.advertisesSignInTVs` there, `TvLoginViewModel`'s
`advertisesSignIn`, set from `BuildConfig.DEBUG`, here): on in debug builds,
off in release until the iOS and Android phone apps that accept `login` TVs
have both shipped. A phone that predates them offers a sign-in TV the setup
chooser and pushes servers without an identity, which the TV refuses. First-run
TVs keep `st=setup` and
the chooser, with the phone's active server preselected. `pushServer` carries
`serverIdentity` and `endpoints` (from `GET /api/v2/system/connections`); the
TV probes the pushed address, offers a verified alternate when it can't reach
it, and never signs in at an address that answers with another identity. A
newer push replaces one in flight. `serverResult.error` is a failure code
(`auth_failed`, `denied`, `expired`, `unreachable`, `identity_mismatch`,
`update_required`). People compare the TV's user code; `match_code` stays on
the wire for the phone's consistency check.

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

Invitation transport remains on its existing v1 domain. Plugin launch/proxy is
unchanged. Password management and session-management UI have no active Android
consumer and are not introduced. Membership runtime activation is separate; none
of its producers or dispatch paths are enabled here. External sign-in is described
below.

## External sign-in (OIDC, LDAP)

Server contract: silo-server `docs/auth-api.md` ("External sign-in", "OAuth
sign-in flows") and `docs/architecture/external-sign-in.md`.

What a sign-in screen offers (`SignInOptions`, shared) comes from
`listAuthProviders` and `getOAuthHandshakeCapabilities`, both read without
credentials at the server's saved URL. The password form shows when
`password_login` is true or any credentials provider is listed; a directory
(LDAP) sign-in sends no `provider`, because the server routes a password login
by account. A server that answers without provider discovery keeps the
password form alone. The phone shows no password form while discovery runs;
when the providers or the handshake capability can't be read (a network
failure, a 5xx or a 429), it shows the password form with a retry for the
other options, and the TV keeps its password option. The phone adds a "Sign
in with <display_name>" button for each oauth provider with a native start
(`native_start_path`; a provider without it offers no native sign-in), on
a server whose handshake capability says `native`, and "Use a different
account" under them when it also says `select_account`. TVs never run a provider sign-in: they hide the password option when the server
lists no credentials provider and `password_login` is false, and sign in with
a phone instead. On a server with an oauth provider the TV's password form
says "If you sign in with <provider>, use your phone instead.", and so does
its wrong-password message.

The phone's native flow (`androidApp/.../auth/`):

- The native start always opens on the saved server's base URL. The app keeps
  only the part of `native_start_path` from
  `/api/v2/auth/oauth/{install_id}/native/start` on, with its query, and
  appends it to the saved base, path prefix included. Any path prefix the
  listing names is ignored, so the app never opens another origin
  (`ExternalSignInApi.nativeStartPath`, `NativeSignInProtocol.startUrl`).
- The caller re-reads the saved server's `server_id` (`getServerIdentity`)
  and passes it, with the saved URL, to `NativeSignInCoordinator.begin`.
  `begin` binds the flow to the saved origin, makes a random `app_state` and
  a PKCE S256 verifier, and keeps the flow in encrypted preferences for its
  10-minute lifetime, so it survives the process being killed while the
  browser is open. The start URL opens in a Custom Tab, never a WebView.
  "Use a different account", and the first provider sign-in on a server
  after an explicit Silo sign-out (Settings, the profile menu, "Not you?
  Switch account"), add `prompt=select_account` when the handshake
  capability says `select_account`; a sign-in after a session expired and a
  linking flow never do.
- `NativeSignInCallbackActivity` receives `org.siloserver.silo:/auth/callback`
  (scheme-only filter, no `autoVerify`; Android can't match a path without a
  host, so `NativeSignInCallback.parse` accepts only that exact path).
- Any app can claim or send the custom scheme, so a redirect counts only when a
  flow is pending, not expired, and its `state` matches in constant time.
  Anything else changes nothing and leaves the pending flow for the real
  redirect. After a match the flow is spent: `iss` (RFC 9207; the server sends
  the origin where the native start first arrived) must be present and equal
  the saved base's origin, compared in the form a browser sends as `Host`
  (lowercase host, IDN names in punycode, IPv6 bracketed and compressed per
  RFC 5952, default port omitted), or the code is discarded with
  "This sign-in came back from a different server. Nothing was signed in.";
  `server` must equal the saved server's verified `server_id`. The code is
  redeemed once, at the saved base only and only while the saved server is
  still at that origin, with the flow's verifier (`completeOAuthLogin`;
  `completeAccountIdentityLink` for a `link=1` redirect, with the session that
  asked for the link ticket).
- `iss` closes a relay through the browser: a hostile saved server whose
  native start redirects the browser to the real server's native start gets
  back a redirect naming the origin where the real server's start arrived,
  its own, which is not the saved origin. It covers only relays the server
  can't be tricked into recording. A hostile server can request the start
  itself with its own origin as `Host`, so the server records a start origin
  other than its public URL only where a server outside the local network
  can't hold it: the origins of connected network access providers,
  loopback, private, link-local and `100.64.0.0/10` IP literals, and
  single-label, `.local`, `.lan`, `.localdomain`, `.home.arpa` and
  `.internal` names; it refuses any other `Host`. A hostile saved server on
  such an address can still relay. The rule and the residual are in
  silo-server `docs/architecture/external-sign-in.md` ("Native apps").
- A sign-in never changes saved servers: a server saved by its LAN address
  signs in on that address and stays there. The app never moves, re-keys or
  duplicates a saved server because of a provider sign-in.
- `error=<reason>` and redemption problems map to text in
  `NativeSignInMessages`, including `account_required` ("You don't have an
  account on this server yet. Ask an admin to add you."); `invalid_grant` and
  `invalid_token` both mean the flow starts again. Codes, verifiers, tickets
  and tokens are never logged.

Account settings "Sign-in" (`SignInSettingsViewModel`) shows only on a server
whose `getExternalSignInCapabilities` says `identities`. It lists the linked
identities and offers Disconnect (`deleteAccountIdentity`) unless the list's
`can_unlink` is false, in which case it says the identity is the account's
only way to sign in; the server still refuses the last sign-in method with
409 `last_sign_in_method`. "Connect" for an oauth provider (handshake `native`
and `linking`) confirms the local password with
`createAccountIdentityLinkTicket` and runs the native flow with `link_ticket`.
"Connect" for a directory (`credentials_linking` in
`getExternalSignInCapabilities`; directory linking needs no handshake) sends
the local password and the directory username and password to
`linkAccountIdentityWithCredentials`. Its two 422 refusals differ only by the
member named (`body.password`, `body.directory_password`); every other refusal
is branched on by problem code, including `local_password_required` and
`already_linked`.

Password login maps `local_login_disabled`, `not_permitted`,
`password_expired`, `provider_unavailable`, `email_in_use`,
`identity_linked_elsewhere`, `account_required` and 429 to their own messages
on phone and TV. A refresh answered with 503 `provider_unavailable` (the
provider could not re-check the account) keeps the session like any other
5xx; the next request refreshes again. A request that needs a fresh bearer
(approving or declining a TV, on the phone or through a nearby TV, on a
pinned server or the active one; reading the account the approval card names;
a link ticket, a link's confirmation and linking with directory credentials)
whose access token has expired and whose refresh got that answer is not sent;
the screen says the provider can't be reached, that the person is still
signed in, and to try again.

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
