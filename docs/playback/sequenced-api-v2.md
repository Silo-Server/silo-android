# Sequenced playback on Android

Phone, TV and online audiobook playback require v2 playback capabilities before
starting. An unavailable endpoint, missing required features or denied admission
produces an unavailable or update-required state. Playback does not fall back to
v1, including after a configured-v2 failure. Admission requires a saved login,
authenticated profile and the server's installation ID. Temporary credentials do
not grant durable playback authority. The retained v1 health probe is not a
playback fallback.

Server runtime and admission remain off by default. Installing this client or
passing its tests does not enable either. Playback requires separately configured
and authorized server admission; unavailable capabilities must remain visible.

## Durable commands and recovery

The shared repository owns the protocol so staged candidates, cast sessions and
orphan cleanup use the same stop path. Start bodies are persisted before sending.
Progress samples receive increasing sequences; an uncertain sample retries with
its original position and paused state before a newer sample is allocated.
Backward seeks can therefore supersede older positions without changing the
bytes of an unresolved sample.

Stops persist one UUID and body before dispatch. Only a matching HTTP 200 stopped
or replayed receipt confirms completion. Draining, network errors and HTTP 503
responses retain the request after the bounded stop retry sequence. Settings
exposes **Retry pending playback stops**. Recovery validates the installation,
canonical account, saved login, origin and profile, resolves an uncertain start
with its original attempt, and stops an allocated session without starting a
renderer. It does not stop a currently adopted in-process player. Identity
changes fence pending requests; stored requests grant no authority.

The app-private journal uses atomic writes and a process ownership lock and is
excluded from Android backup. It stores request identities and bodies without
credentials. Clearing application storage removes this recovery state. Existing
queued intents retain their original bytes and authority; they are not converted,
reset or replayed as new v2 intents.

Generic errors, including HTTP 409, 422 and 503, do not prove that an earlier
allocation is absent. They retain uncertain START requests. Recovery may replay
the exact retained attempt under its original validated authority; it must not
rebase the request, allocate a replacement attempt or fall back to v1. The
specific retained terminal decisions described below are the exceptions.

## Lost server owner recovery

Exact retained START and STOP requests may return an additive owner-loss union.
HTTP 202 `outcome: "draining"` retains the request and binds the nested recovery
identity. The required fields are `recovery_id`, `playback_attempt_id`,
`session_id`, `state` and `reason: "owner_lost"`. The recovery ID and original
attempt/session must remain stable across observations and journal reloads. A
pending START learns its session only inside this recovery binding; it does not
acquire an active session or renderer authority.

A completed START uses HTTP 201, `outcome: "adaptation_unavailable"`,
`terminal.reason: "playback_owner_lost"`, `terminal.retryable: false` and
`recovery.state: "aborted"`. It carries no playable plan, top-level session or
progress timeline. A completed STOP uses HTTP 200, `outcome: "aborted"` and the
same terminal recovery object, without a top-level `stop_id`, `accepted` or
`history_id`. A real matching ordinary StopID receipt keeps its existing meaning
when no owner-loss recovery has been observed. A validated recovery response
binds the committed AbortID: subsequent replies must retain that recovery union,
including after reload. An ordinary receipt cannot supersede the binding.
The `playback_owner_lost` terminal reason requires complete recovery proof and
cannot enter ordinary START decoding. Mixed, malformed or wrong-status unions
are uncertain.

The journal records terminal abandonment separately from ordinary STOP success,
retaining original requests and raw recovery responses. Optional
`recovery.accepted` is only the server's captured Last sample; no Last means no
accepted sample. Its sequence must be positive, position finite and nonnegative,
and paused state explicit. Bound samples must match the original saved timeline
and its exact part-local/global mapping. The queued final sample remains
unapplied, even if its sequence is newer than the server's Last.

Abandonment releases the journal recovery blocker but returns a distinct terminal
owner-loss error to normal STOP callers, including subsequent calls for that
binding. The native lifecycle and navigation barriers halt audiobook part changes
and next-item playback, with an ended-playback message instead of a Retry STOP
prompt. Explicit recovery may retire the binding; a later explicit user action
may create a new attempt. Recovery never starts a renderer, restores media rights,
automatically starts another item, or changes an old request's authority.
Reservations without captured activation and source withdrawal remain uncertain.
This contract adds no feature token, owner takeover or runtime activation.

## Bound audiobook timelines

Online audiobooks require `bound_client_timeline` and `sequenced_progress_v1`.
Explicit discovery uses
`GET /api/v2/playback/timelines/{file_id}?installation_id=...` under the captured
viewer and installation. The complete manifest supplies `installation_id`,
`timeline_id`, `media_item_id`, `edition_id`, total `duration_seconds` and ordered
parts with `file_id`, `offset_seconds` and `duration_seconds`.

The retained manifest controls initial resume, next-part selection and cross-part
seeks. Detail metadata may supply chapter labels within trusted part spans, but
cannot replace the server's offsets or durations. START sends
`progress_persistence: "client_bound"`, the discovered `timeline_id`, the
`bound_client_timeline` client feature and an explicit part-local
`start_position`. The returned `progress_timeline` must match the captured part.

Progress and stop positions remain part-local and carry `timeline_id`. Stop
requires that identity even when it has no final sample. An accepted receipt
contains local `position`, global `item_position` and `timeline_id`; zero is a
valid global position. The client validates both clocks against the retained
mapping and persists accepted global resume under the same owner and timeline.
It does not upload bound progress through the personal-data outbox.

A next-part start or cross-part seek waits for the old part's terminal stop
receipt. Unknown stop outcomes retain the old session, exact stop and intended
target, and hold the transition. Pressing Play can reconcile that same pending
stop; it cannot replace an uncertain START. Local renderer retirement alone is
not a server terminal receipt.

## Definitive timeline-change refusal

The server reserves the exact START attempt before deciding that its trusted
manifest changed. The definitive refusal is a retained HTTP 201 ordinary START
decision with:

```json
{
  "protocol_version": 3,
  "outcome": "adaptation_unavailable",
  "terminal": {
    "reason": "client_timeline_changed",
    "message": "Open the book again to use its updated timeline.",
    "retryable": false
  }
}
```

This excerpt omits the envelope's negotiated feature list. The message is
illustrative; the discriminator is `terminal.reason`, not `reason_code`.
There is no session, playback plan, route or activation. Exact attempt replay
returns the retained terminal before consulting current catalog metadata. Lost
terminal publication responses remain HTTP 503 or network uncertainty until
exact replay resolves them. Generic HTTP 409 and interim timeline-change HTTP
409 responses are not definitive refusals.

After validating this exact terminal decision for the original request and
current captured authority, the client durably retains the original request,
manifest and terminal receipt, settles that attempt and invalidates its cached
discovery. It does not automatically refresh or restart. A **new explicit user
intent**, such as reopening the book, must discover a fresh manifest and use a
distinct attempt ID. Old attempt IDs cannot be repurposed. Malformed terminals,
missing or true `retryable`, and responses containing an allocated session or
plan do not discharge uncertainty.

## Other transports and validation limits

Negotiated sessions use v2 control, replan and route-event operations. Unsupported
replan responses remain unavailable; a documented HTTP 501 rejection can settle
that replan without claiming a replacement plan exists. Uncertain mutations
retain their exact authority and bodies. Negotiated sessions do not trigger a
legacy outage replacement or write progress through the personal-data outbox.
File IDs remain JSON strings in requests and the journal; the renderer accepts
only canonical positive IDs representable by its integer model.

Focused tests cover persisted commands, receipt validation, manifest selection,
terminal-before-start ordering, lost replies, restart recovery, identity changes,
explicit fresh intent after definitive refusal and shared lifecycle behavior.
Phone and TV builds check caller and Settings integration. These checks do not
establish rendered media behavior, device process-kill durability, live server
admission or physical TV acceptance. Actual isolated native media validation is a
separate requirement; client conformance does not activate the server runtime.

## Proxy auxiliary subtitles

A proxy subtitle artifact must identify the issued proxy origin and session with
an absolute credential-free URL:
`/stream/v3/{session_id}/subtitles/{nonnegative track}.{format}`. Supported route
suffixes are `ass`, `ssa`, `srt`, `vtt` and `sup`; renderer support still determines
which artifacts can be mounted. Preserve the issued `file_id` and immutable
`embedded_stream_index`, `external_subtitle_key` or `downloaded_subtitle_id`
selectors, including their encoded bytes. The matching explicit response and
plan `session_id` bind auxiliary references to the playback session. A signed
primary stream supplies the issued origin; its opaque path is never parsed to
recover identity. Captured auxiliary headers are not attached to that signed
primary or to unissued sibling routes. PGS `windowed`, `position` and
`duration` options remain unchanged. Duplicate pins are preserved for the
producer's authoritative rejection. Do not append an access token or substitute
`source_file_id` for `file_id`.

The server publishes references, not viewer credentials. Its auxiliary producer
requires a live captured `Authorization: Bearer ...` header and matching
`X-Profile-Id` selector. The recipe's signed profile remains authority; the
selector is not proof. Android captures the headers actually sent on the owning
START or replan request and joins them to the validated artifact only in
transient native request state. Bearer credentials are excluded from serialized
plans and durable journals. A changed identity, stopped session or replacement
plan invalidates the old scope. A later data-source open cannot substitute the
ambient token or selected profile, refresh auxiliary credentials, or send them
to another origin, session, path or changed subtitle query.

The actual subtitle data source uses those captured headers only for issued
references. Auxiliary redirects and automatic connection-failure retries are
disabled. Existing signed `/stream/subtitles/{token}/...` references and API
executor references with opaque `st` remain separate URL families. Android has
no runtime font-bundle loader in this path; DTO inventory does not establish
`/fonts` consumption or justify creating one. The URL-only Cast receiver cannot
supply the proxy header contract; Cast preparation refuses proxy v3 routes before
its existing query-signing step rather than appending an access token.

This requires the server proxy auxiliary producer, immutable artifact
construction and configured admission to be joined and reviewed. Native source
and transport tests do not establish that runtime wiring or media acceptance.
