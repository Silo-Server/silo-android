# Sequenced playback on Android

Phone, TV and online audiobook playback require v2 playback capabilities before
starting. An unavailable endpoint, missing required features or denied admission
produces an unavailable or update-required state. Playback does not fall back to
v1, including after a configured-v2 failure. Admission requires a saved login,
authenticated profile and the server's installation ID. Temporary credentials do
not grant durable playback authority. The retained v1 health probe is not a
playback fallback.

Every playback v2 call goes through the shared `ApiV2Gate`, so a server in the
update-required state is refused locally without an HTTP exchange. Start returns
HTTP 201 `PlaybackDecision` (`outcome` playable or adaptation_unavailable);
progress and stop return HTTP 200 `PlaybackMutation` (`outcome` applied,
replayed, stale_sample or stopped); route events return HTTP 202 with the echoed
`event_id`. Capability `state` is available, disabled, not_configured or
unsupported; Android reads `installation_id`, `state`, `allowed`,
`protocol_versions` and `features` only.

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
or replayed receipt confirms completion. Network errors and HTTP 503 responses
retain the request after the bounded stop retry sequence. A later start for
the same login, server and profile first runs recovery inline and only refuses
with `playback_pending` when the retained request still cannot settle; Settings
also exposes **Retry pending playback stops**. Recovery validates the installation,
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
rebase the request, allocate a replacement attempt or fall back to v1.

## Audiobooks

The server has no whole-book timeline. The audiobook player stitches the
item's part files into one client-side timeline from detail metadata
(`buildAudiobookTimeline`), plays one part per session with
`progress_persistence: "client"` and a part-local `start_position`, and
resumes from the detail's whole-book progress position. Progress and stop
samples are part-local; whole-book resume is written through the personal
data path, not the playback session.

## Other transports and validation limits

Negotiated sessions use v2 control, replan and route-event operations. Unsupported
replan responses remain unavailable; a documented HTTP 501 rejection can settle
that replan without claiming a replacement plan exists. Uncertain mutations
retain their exact authority and bodies. Negotiated sessions do not trigger a
legacy outage replacement or write progress through the personal-data outbox.
File IDs remain JSON strings in requests and the journal; the renderer accepts
only canonical positive IDs representable by its integer model.

Focused tests cover persisted commands, receipt validation, lost replies,
restart recovery, identity changes and shared lifecycle behavior.
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
