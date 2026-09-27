# Android Watch Party implementation plan

Build Watch Party for Android phone and Android TV on one shared room owner and
one shared player binding over the existing Media3 players, with native Compose
screens. Follow the current server contract, including its September 23
buffering and catch-up policy. Use the merged Apple client as the reference
native implementation. Ship behind a device-local Experimental toggle, off by
default in release builds.

Status: planning only. No Android implementation or playback qualification is
claimed here. This plan replaces the September 21 draft, which predates the
server's buffering policy and Apple's merged client. The June and July Watch
Together plans under `docs/superpowers/` remain history.
[Android playback architecture](playback/README.md) continues to own Media3,
playback-v3, timeline mapping, and output-route behavior. Tracking issue:
[Android #355](https://github.com/Silo-Server/silo-android/issues/355).

## What changed from the September 21 draft

- The server now has a buffering and catch-up policy: a 2-second buffering
  grace, `self_ignore_wait`, `ready` while the room plays or is paused, no
  position reports while stalled, rate convergence, and paced correction
  reloads. The draft's 500 ms stall rule and "no second drift controller" rule
  contradicted it.
- The snapshot anchor is already projected to send time. Projecting it again
  counts elapsed playback twice (Apple fixed this in #347).
- A host who leaves ends the room two minutes later unless they rejoin. There
  is no leave route; the room closes two minutes after the host's socket drops.
- Socket rotation needs a token refresh path for the ticket mint. The draft's
  `singleAttempt()` rule would have ended the party at an ordinary rotation.
- Exposure follows Apple: an Experimental toggle, not a debug-only build flag.
- The player section lists every Android input and session-replacement path,
  including code that landed after the draft (seek intervals, extras, named
  editions, Force HDR passthrough).
- UX adds in-playback status, host-pick suggestions, end-of-file handling,
  a lower-quality offer, other-playback rules, and a background table.

## Sources and baselines

| Source | Revision | Use |
| --- | --- | --- |
| Android | `main` at `03ac5b71` (2026-09-24) | Code baseline. Watch Together sources are unchanged since `6e26ae46`; the player gained seek intervals (#365), extras (#369), Force HDR passthrough (#360), and named editions |
| Server | `main` at `2812ac39` (2026-09-24) | Contract owner: [room phases](https://github.com/Silo-Server/silo-server/blob/2812ac390829bec2b64328dcee29f0eb15a9b808/docs/architecture/watch-together-rooms.md), [realtime API](https://github.com/Silo-Server/silo-server/blob/2812ac390829bec2b64328dcee29f0eb15a9b808/docs/realtime-api.md), [synchronization and buffering policy](https://github.com/Silo-Server/silo-server/blob/2812ac390829bec2b64328dcee29f0eb15a9b808/docs/architecture/watch-party-synchronization.md), [capabilities](https://github.com/Silo-Server/silo-server/blob/2812ac390829bec2b64328dcee29f0eb15a9b808/internal/apiv2/watch_together_capabilities.go) |
| Web reference client | Same server revision | Client timing reference: [`useWatchTogetherPlaybackSync.ts`](https://github.com/Silo-Server/silo-server/blob/2812ac390829bec2b64328dcee29f0eb15a9b808/web/src/player/hooks/useWatchTogetherPlaybackSync.ts), [`roomSyncCatchup.ts`](https://github.com/Silo-Server/silo-server/blob/2812ac390829bec2b64328dcee29f0eb15a9b808/web/src/player/utils/roomSyncCatchup.ts) |
| Apple | `main` at `5d9dc764`, including [#347](https://github.com/Silo-Server/silo-apple/pull/347) | Native reference: session, playback adapter, end of file, Experimental gate, host-pick suggestions |
| Shared-dev | Record at every run | Must run server code that includes `9482302b` and advertise every required capability |

When sources disagree, server code wins, then server docs, then the web
client, then Apple. Apple still lags the server on buffering: it reports
buffering after 0.5 s and has only a small 0.95x/1.05x correction, with no
catch-up acknowledgement, reload budget, or lower-quality offer. Follow the
server there.

On 2026-09-24, shared-dev's `/api/v2/admin/system/build` returned
`available: false`. Read the running image and revision through the runtime
and fleet tooling instead. The last recorded inspection (2026-09-22) showed
one API node with workers disabled. That runtime cannot qualify HLS, transcode,
remux, or cross-node cases.

## Product decisions

| ID | Decision | Basis | Status |
| --- | --- | --- | --- |
| D1 | Add a device-local **Settings → Experimental → Watch Party** toggle on phone and TV. Debug builds default on; release builds default off. The section is visible to everyone. Turning it off hides every entry point, ignores invitations, and leaves an active party (for a host, the room ends two minutes later unless they rejoin). A server reporting `allowed=false` or missing capabilities is the remote kill switch. | Apple #347 and `5d9dc764` | Agreed 2026-09-24. Record it in `AGENTS.md` "Current Product Exposure" in the change that adds the toggle |
| D2 | Everyone gets **Leave party**. The host also gets **Return everyone to lobby** (Stop) and **End party for everyone**. A host's Leave explains that the party ends two minutes after they leave unless they rejoin. | Apple's room menu; no leave route; 2-minute host grace | Adopted |
| D3 | No new foreground service keeps the room socket alive. Background behavior follows the table under "Background and process death". Background hosting is not promised. | 2-minute host grace | Adopted |
| D4 | Invitations: room code everywhere; phone share and copy of the server's `https` invitation link (custom-scheme links aren't clickable in Messages or email); a paste parser; a TV QR code of the link. The app link is `silo://watch-party?server=<server URL>&token=<join token>`, and it resolves to the same invitation as the web link. Tapping a shared `https` link opens the app only after the server's join page offers that app link. | Server `invite_path`; [Apple #409](https://github.com/Silo-Server/silo-apple/issues/409); format proposed in [Apple #412](https://github.com/Silo-Server/silo-apple/pull/412) | Not final. Build against #412's format; freeze it before the M5 link handling merges |
| D5 | While engaged, solo playback (detail Play, extras, launcher and deep links, SiloCast launch) asks the user to leave the party first. An invitation that arrives while engaged explains that the user must leave first. | Invitation message from Apple #347; the solo-playback guard is an Android decision (one shared service player) | Adopted |
| D6 | Catch-up follows the server policy and the web client: 2-second buffering grace, rate convergence between 0.9x and 1.25x kept separate from user speed, paced correction reloads, and a lower-quality offer. User playback speed is a session-only 1x. | Server sync doc; web client | Adopted |
| D7 | Party PiP, Google Cast, and external players stay disabled in rooms until each route is qualified. Starting party playback ends a solo PiP session. The party player blocks Cast connection. | Apple disables party PiP and AirPlay and ends solo PiP; Cast and external players are the Android equivalents | Adopted |
| D8 | In a party, intro and credits never skip automatically. Members who may seek get the Skip Intro offer (sent as a room seek) even when their setting is Always. Members who may not seek get no intro or credits pill. | Apple #347 | Adopted |
| D9 | Suggestions work in both modes. In a host-pick lobby, the host's **Queue** stages a suggestion and labels it "Up next"; it never promotes. While engaged, a guest's detail page offers **Suggest to Party**. The host's detail action stages the item in the lobby and selects it while playing. Android adds a confirmation before a selection that replaces what everyone is watching. | Web client; Apple #347 (Apple selects without a confirmation) | Adopted |

## Scope

| Area | Required behavior |
| --- | --- |
| Entry | Experimental toggle (D1); profile-menu hub; More action on movie and episode details and series (resolves to the next-up episode); Return to Party while engaged. Navigation tabs are unchanged |
| Host | Host Picks by default; create an empty room, or create and stage the chosen item; the host may start alone |
| Join | Room code, pasted invitation, validated invitation link, explicit recent-party rejoin; deferred sign-in and profile selection are preserved |
| Lobby | Empty or staged item, members and seats, advisory lobby Ready, host Start, mode switch, guest control policy, options menu with End as the only destructive action |
| Suggestions | Add, remove (own, or any as host), vote and unvote, winner display, host override when advertised, host-pick Queue (D9) |
| Picker | Server picker rows, search, series, season and episode navigation, selected-candidate member state |
| Playback | Server-owned play, pause, seek, and source; per-viewer delivery, quality ladder, audio, subtitles, and volume |
| Catch-up | Server buffering policy with client rate convergence, reload budget, and lower-quality offer (D6) |
| Party controls | Phone sheet and TV overlay over retained playback; Back opens them; Stop, End, and Leave per D2 |
| Status | Waiting and catching-up overlays, left-behind and host-disconnected notices, delayed reconnect notice, denied-action notices |
| Invitations and recents | D4; one recent party per identity for 24 hours |
| Recovery | Socket rotation, proof renewal, replacement and rejoin, identity cleanup, session reattachment, coordinated source fallback |
| Next episode | Optional, last: an explicit host Next Episode action in host-pick rooms through the selection operation |

Out of scope: downloads, live TV, music, audiobooks, ebooks, chat, reactions,
kicking members (no server endpoint), host transfer, automatic episode queues,
activity feeds, and a room-aware SiloCast launch handoff. Admin surfaces stay
hidden. Suggestions are not a queue.

## Server contract

All paths are under `/api/v2`. Wire names keep `watch-together`; user-facing
text says Watch Party.

### Capabilities

Two documents gate the feature. Cache each within the captured authority, and
refresh on a server, account, or profile change.

- `GET /watch-together/capabilities` (account authentication, ETag
  revalidation): `state`, `allowed`, `socket_protocol`, `connection_replaced`,
  `lobby_ready`, `staged_selection`, `stop_playback`, `selection_mode_switch`,
  `picker`, `member_state`, `max_member_state_ids`, `vote_host_override`.
- `GET /playback/capabilities` (profile authority; PIN profiles also need their
  profile token): `features[]` contains `watch_party_coordinator_v1`,
  `fixed_media_file_v1`, and `watch_party_source_fallback_v1`.

Entry requires room `state=available`, `allowed=true`,
`socket_protocol == "silo.room.v2"` (an empty string means no v2 socket) and
`connection_replaced=true`, plus a playback document that is available, allowed,
and lists the coordinator and fixed-file features. Unknown phases, roles, or
policies grant no actions.

| Flag false or absent | Behavior |
| --- | --- |
| Required baseline | No create, join, or room playback. Show "not supported" or "not allowed"; a failed probe shows Retry and keeps any known state |
| `staged_selection` | Disable hosting and staging; joining an existing room still works. Never present direct selection as staging |
| `stop_playback` | Hide Stop; keep End and Leave |
| `selection_mode_switch` | Hide the mode switch |
| `lobby_ready` | Hide lobby Ready; never substitute playback readiness |
| `picker` | Use ordinary authorized catalog browsing |
| `member_state` (`max_member_state_ids` = 0) | Omit member watch state; never infer it from local history |
| `vote_host_override` | Hide promotion entirely. Without it, promote returns 503 |
| `watch_party_source_fallback_v1` | Keep the playback refusal visible with Retry and Leave; never pick another file locally |

With the coordinator feature, omitted member status booleans mean false.
Without it, absence is not evidence of readiness.

### Operations

"Proof" means the `X-Room-Token` room access token. Retry classes come from
each operation's server `RetrySafety`.

| Operation | Route | Needs | Retry class |
| --- | --- | --- | --- |
| Create room | `POST /watch-together/rooms` with client UUID `room_id`, `selection_mode` (`host_pick` or `vote`); returns 201 | Account and profile | Replay the same ID and body |
| Join | `POST /watch-together/join` with `code` or `join_token` | Account and profile | Idempotent; each call can return a different proof |
| Read and renew | `GET /watch-together/rooms/{id}` | Proof | Idempotent; returns a renewed `room_access_token` |
| End | `DELETE /watch-together/rooms/{id}` | Host account and profile | Converges on ended |
| Policy | `PATCH .../policy` (`host_only` or `guest_play_pause`) | Host | Idempotent; use the returned policy |
| Stage | `PUT .../staged-selection` | Host | Idempotent |
| Start | `POST .../playback/start` | Host | **Non-retryable** |
| Stop | `POST .../playback/stop` | Host | Idempotent |
| Mode | `PATCH .../selection-mode` | Host | Idempotent |
| Select | `PUT .../selection` (`content_id`, optional `file_id`, `library_id`) | Host; not in vote rooms | **Non-retryable** |
| Source fallback | `POST .../source-fallback` (`selection_revision`, `failed_file_id`, `reason`) | Proof and a connected member | Fenced; a stale call returns the current snapshot |
| List suggestions | `GET .../suggestions?limit=&cursor=` (limit 1–200, default 50) | Proof | Idempotent |
| Add suggestion | `POST .../suggestions` with client UUID `suggestion_id` | Proof | Replay the same ID and body |
| Remove suggestion | `DELETE .../suggestions/{sid}` | Proof; host or suggester (account and profile) | A repeat returns 404 |
| Vote and unvote | `POST` and `DELETE .../suggestions/{sid}/vote` | Proof | Returns 204, including when already in the requested state |
| Promote | `POST .../suggestions/promote` | Proof and host; hidden without `vote_host_override` | **Non-retryable** |
| Member state | `POST .../member-state` with 1–`max_member_state_ids` IDs | Proof | Idempotent read |
| Picker | `GET .../picker` | Proof | Idempotent read |
| Socket ticket | `POST .../ws-ticket` | Proof and an expiring access session | Safe to repeat |
| Socket | `GET .../ws` upgrade with the ticket as a subprotocol | Ticket | Not applicable |

End, policy, stage, start, stop, mode, and select need host identity, not
proof. Promote needs both. Every successful room response carries a renewed
`room_access_token`: create, join, read, policy, selection, stage, start, stop,
mode, promote, and source fallback.

The server gives only the host an `invite_path`: a relative web path,
`/rooms/join?token=<24-character token>`. Room codes are 8 characters from
`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`. The server trims input and matches codes
without regard to case, but it does not strip separators.

### Socket frames

- Server to client: `snapshot`, `transport_command`, `suggestions_update`,
  `pong`, `error` (code `bad_request`), `connection_replaced`, and
  `room_closed`. At connect time, `room_closed` carries `not_found` or `ended`.
  Every later close, including the host's explicit End, carries `host_left`,
  so the reason cannot tell End apart from grace expiry.
- Client to server: `attach_session`, `transport_request`, `state_report`
  (optional `command_id` and `is_ready`), `ready` (`session_id`,
  `position_seconds`, `is_paused`, `command_id`), `buffering`, `lobby_ready`
  (`ready`), `ping`. There is no `client_state` frame.
- Ignore unknown frame types. A malformed frame of a known type triggers a
  logged room read, not the end of the party. Apple ends the party here and
  lists that as an open issue.
- `suggestions_update` carries the common list in `vote_count` descending, then
  `created_at` ascending order, with every `voted_by_me` false. That field is
  not authoritative; personal votes come from HTTP reads.

Model these snapshot fields: `room_id`, `phase` (`lobby`, `playing`, `ended`),
`playback_state` (including `waiting`), `selection_mode`, `selection_revision`,
selected content, file, and library IDs, `code`, `guest_control_policy`,
`is_paused`, `anchor_position_seconds`, `anchor_updated_at`, `generation`,
`member_count`, `host_connected`, `self_role`, `self_can_control_transport`,
`self_can_manage_room`, `self_ignore_wait`, `attached_session_id`,
`invite_path`, and `members[]` with `user_id`, `profile_id`, `display_name`,
`is_host`, `is_self`, `connected`, `lobby_ready`, `is_ready`, `is_buffering`,
and `is_syncing`. Take the complete shape from the fixtures. Android's `RoomSnapshot` has no `members` today. HTTP v2 IDs are
strings; socket frames send several of them as numbers.

### Timing and limits

| Owner | Value |
| --- | --- |
| Server | Ticket lifetime ≤ 30 s (`expires_in` is 0 when the access token or the room proof has under a second left). Socket closes at the earliest of 5 min, access-token expiry, or proof expiry. Room proof is an HS256 JWT with `exp` 24 h after issue. Authority recheck every 15 s |
| Server | Host-disconnect grace 2 min. Presence lease 45 s. Idle room expiry 24 h. Socket write queue 64 frames; a full queue closes the socket |
| Server | Waiting deadline 10 s, and only once at least one attached member is ready; past it, the first `ready` resumes the room. Seek tolerance: guest 1 s, host 15 s (a host more than 1 s away re-anchors the room). Maximum command lead 5 s |
| Server | Buffering pauses the room only if the viewer has not stalled in 5 min, the room has not paused for buffering in 1 min, and the viewer is not already catching up. A lone viewer's stall always pauses and waits past the deadline. A buffering member gets no corrections for up to 30 s. A `state_report` within 1 s of the room with the same pause state marks the member ready. A host drifting 2 s or less is corrected like a guest; a larger jump or a pause mismatch moves the room |
| Server | `max_member_state_ids` = 200. Picker rows are capped at 20 and 30. Suggestion pages default to 50 |
| Client (web reference; tune only with evidence) | State reports every 1.5 s. Readiness retry every 500 ms. Ping every 15 s. Command quiet period 250 ms. Buffering report after 2 s of unplayable media. Catch-up band 2 s, deadband 0.35 s, rate 0.9–1.25x at `1 + drift / 8 s`. Reload budget: one at a time, 10 s doubling to 60 s, 30 s stale limit, lead ≤ 10 s, capped at the media duration. Reconnect notice after 2 s disconnected. Lower-quality offer after 2 sustained stalls within 5 min |

### Errors

| Response | Handling |
| --- | --- |
| 401 | Retry-safe operations, including the ticket mint: refresh under the same logical authority once, then resend. This follows Apple; the web client treats a ticket 401 as terminal, and the server marks the mint naturally idempotent. Non-retryable operations: refresh before sending when the token is near expiry; a 401 leaves the outcome uncertain, so reconcile |
| 403 `permission_denied` or `profile_verification_required` | Proof or authority failure on membership operations ends the engagement. A guest calling a host operation gets an action-level denial |
| 404 | On a room operation, the room is gone: end the engagement. On Join, an unknown code or token: action-level. A missing suggestion: action-level |
| 409 | Ambiguous: it means both a closed room and an ordinary conflict (vote room, nothing staged, already playing, no fallback candidate, reused ID). Reconcile with `GET /rooms/{id}`; a 409 there means closed |
| 422 | Action-level invalid input, such as a missing code or an unplayable selection |
| 429 `rate_limited` | Action-level; honor `Retry-After` |
| 503 | Transient; retry reads, and reconcile mutations |
| Malformed 2xx | `invalid_response`. For a mutation, the outcome is uncertain: reconcile, and never report success or failure blindly |
| Ticket `expires_in: 0` | Recoverable: refresh the access token or renew the proof, whichever is expiring, then mint again |
| Ticket 403, 404, 409, 422; `room_closed` at connect | Terminal |

Use `detail` text for display only, never for control flow.

## Design and ownership

| Boundary | Responsibility | Code to evolve |
| --- | --- | --- |
| Wire and API | Typed capabilities, operations, frames, response validation, retry classes, error mapping | `shared/.../model/watchtogether/WatchTogetherModels.kt`, `network/api/WatchTogetherApi.kt`, `network/WatchTogetherRealtimeClient.kt`, `network/apiv2/ApiV2Call.kt` (`ownedV2Call`) |
| Room engagement | One app-level engagement: authority, membership, proof, socket, reconnect, clock, snapshot ordering, actions, votes, recent party | `shared/.../watchtogether/RoomSession.kt`, `repository/WatchTogetherRepository.kt`, `RoomTransportAuthority.kt`, `RoomVote.kt`, `WatchTogetherEntryPolicy.kt` |
| Player binding | Room playback context, paused preparation, attach, command scheduling, readiness, reports, catch-up, reload budget | New `android-shared/.../player/watchtogether/`; absorbs `RoomSyncEngine.kt`, `RoomDeliveryLatch.kt`, `RoomSyncStateReportGate.kt`, and the duplicated mechanics in both controllers |
| Player adapters | Connect the binding to each player: observed position, completed load and seek, buffering, media mount identity, input gating | `RoomSyncController.kt`, `TvRoomSyncController.kt`, both player ViewModels, `MobileVideoPlaybackStarter.kt`, `TvVideoPlaybackStarter.kt` |
| Shared presentation | Entry, lobby, suggestion, and picker state and actions; eligibility; pending and error state; domain destinations | Merge the duplicated phone and TV ViewModels into `shared/` or `android-shared/` |
| Platform UI | Phone layout and sharing; TV layout, focus, and QR; route construction | Both `ui/screens/watchtogether/` directories, menus, detail actions, settings, navigation |

The application owns one engagement. Its room socket survives ordinary
navigation. The player binding owns only the current playback epoch and its
player observers. The room socket and the playback-control socket keep
independent connection state and recovery.

Keep these identities distinct:

- **Authority:** verified server (base path included, matched with
  `AndroidServerRegistry.serverIdsMatch`), `loginId`, profile, PIN scope, and
  identity generation. Unlocking the same PIN profile again does not change the
  owner.
- **Membership:** authority, room ID, and engagement generation. Joining the
  same room again creates a new membership.
- **Proof:** the renewable room token and its `exp`. Renewing proof never
  changes membership.
- **Socket attempt:** connection epoch.
- **Playback epoch:** membership plus `selection_revision`.
- **Attachment:** playback epoch plus the committed playback session ID.
- **Command:** command ID, with pending and applied state.

Every asynchronous result checks the identities its effect depends on.
Replacing the account, profile, PIN authority, or server cancels old work
before the new identity can use it.

### Room transitions

| Accepted state or event | Client action |
| --- | --- |
| Lobby, no selection | Empty lobby; no player |
| Lobby, staged item | Staged lobby; no player |
| Stage, restage, or mode switch | Update the lobby and lobby Ready; no playback epoch |
| Playing with a new `selection_revision` (Start, Select, Promote, fallback) | Cancel the old epoch; prepare the exact selected file, paused |
| Playing with a membership-only or anchor-only update | Update room state; keep the player |
| `playback_state=waiting` | Apply the waiting command; acknowledge readiness (see Readiness) |
| Playing or paused with `self_ignore_wait` or own member `is_buffering` | Catching up (see Buffering) |
| `host_connected=false` while engaged | Tell guests the party ends two minutes after the host drops off unless the host returns |
| Host Stop | End the playback epoch; show the retained staged lobby; keep membership |
| Ended snapshot or `room_closed` (any reason) | End the engagement, player, reports, commands, and reconnects. Say the party ended; say who ended it only when this device did. Clear the recent party. No current server path sends an ended snapshot, but keep the check |
| `connection_replaced` | End this device's engagement; offer explicit Rejoin; the room continues |
| Leave or identity replacement | Cancel work, stop the room player, close the socket, clear identity-scoped state. For a host, the room ends two minutes later unless they rejoin |

Snapshot acceptance rejects results from an older room, authority, revision, or
generation. An HTTP response with the same generation must not erase a newer
socket attachment or readiness update: track socket receipt order as well as
generation. A stale response cannot reopen a terminal engagement. Accept a
renewed proof from a response even when its snapshot body is stale.

## Playback rules

### Room playback context and start

- Build the context from authority, room, `selection_revision`, content, file,
  library, the snapshot's paused state, and `anchor_position_seconds` **as
  sent**. The server has already projected it; the command sent after attach
  corrects the remaining drift. Resolve series or season entry to a playable
  episode before building the context.
- Pass the context through both starters. Room starts send
  `allow_alternate_versions: false`, and only to servers that advertise
  `fixed_media_file_v1`. The field is nullable so solo starts omit it. The
  server stores it with the attempt and keeps it through every replan. Refuse
  any plan for a different file.
- Room playback always streams: bypass downloaded copies. Never fall back to
  personal resume or a preferred alternate version. If the chosen file is
  inaccessible, fail with a clear message and follow the fallback rules below.
- Prepare paused. Nothing plays until an accepted room command says so.

### Session replacement paths

Each of these paths can create a new playback session. Each must carry the room
context, start paused at the room position, keep the fixed file, and hold
reports until the matching attach echo arrives:

- 404 session renewal, the only path that uses `StartParams` today
  (`PlaybackSessionLifecycle.kt`).
- `replanActiveVideoSession` (`PlaybackSessionManager.kt`), reached from a
  phone output-route change, an embedded-subtitle failure, an audio failure, a
  subtitle inventory change, a player error, and a queued re-drive.
- `stageActiveVideoSessionReplan` (`MobileSubtitleTransactionAdapter.kt`,
  `TvSubtitleTransactionAdapter.kt`), reached from user subtitle changes and
  the TV output-route change.
- `onUnsupportedPlayback` in both ViewModels.
- Seek recovery and seek re-anchoring in `PlaybackSessionManager.kt` and both
  ViewModels.
- Phone full restarts: `startVersionPlayback`, subtitle-adoption recovery, and
  `replayLoad` (Retry, Try Anyway).
- TV full restarts: `restartSessionInPlace`, reached from the version picker
  and the Dolby Vision toggle; `retry` and the reachability retries call
  `loadContent` directly.

Both ViewModels currently publish `isPaused=false` when the player becomes
ready. Room playback must not.

### Attach and report

- Attach the committed session after every room-socket epoch and every session
  ID change. Wait for the matching `attached_session_id` before reporting. A
  playback-control socket reconnect for the same session needs neither.
- Send a `state_report` every 1.5 s built from observed player state: the
  source-timeline position from the existing timeline mapper, and whether the
  player is actually playing. Today's reports send the intended paused state and
  an optimistic position; replace that.
- Send no report while stalled, while the media is unplayable and a readiness
  acknowledgement is pending, during provisional preparation or replacement,
  during a local suspension, or before re-syncing after one.

### Commands and clock

- Apply accepted commands through an internal path, separate from user
  transport requests, so they are never echoed back as intent.
- Sample the clock with ping and pong from socket open, before a player
  attaches. Stamp pong receipt at transport ingress and fence samples by
  connection epoch. Reject invalid samples and detect clock discontinuities.
  Pings run every 15 s.
- Schedule with the server-clock estimate and a monotonic delay (at most the
  5 s lead). A late Play advances its target by the elapsed server time. With
  no valid sample, stay paused at the snapshot anchor, obtain one, and bound
  that wait with a recovery offer instead of assuming zero offset.
- Track pending and applied command IDs. A superseding command cancels delayed
  work. A cancelled command never executes later and is never acknowledged.
  Recheck ownership immediately before execution.

### Readiness while the room waits

- Readiness is level-triggered and keyed to the current command, epoch, and
  session. Evaluate it on every Media3 signal that can mean playable media
  (`STATE_READY`, a seek discontinuity, `onIsPlayingChanged`, first frame) and
  on a periodic tick. It requires: the latest command executed, no pending
  seek, playable media, and, for an explicit seek, an actual position within
  1 s of the destination (15 s for the host).
- Send `ready` with `command_id`, the actual position, and the pause state.
  Retry every 500 ms, and also send `state_report` with `command_id` and
  `is_ready: true`, until a fresh snapshot on the current connection marks the
  own member `is_ready`. Socket delivery alone does not settle it, and neither
  does an `is_ready` that predates the command. Then return to ordinary
  1.5 s reports.
- A second seek on the same session re-arms readiness even if buffering never
  changes and the room stays waiting.

### Buffering and catching up

- Stalls under 2 s stay local. After 2 s of unplayable media while the room
  plays, send one `buffering`. Recovery, a new command or session, a pause, a
  phase change, a disconnect, or disposal cancels a pending report.
- The room is catching up for this viewer when the phase is `playing`, the
  playback state is `playing` or `paused`, and the snapshot shows
  `self_ignore_wait` or the own member `is_buffering`. Once the latest command
  has executed and the media is playable, send `ready` and retry every 500 ms
  until the own member is `is_ready`. The server clears the status. While the
  room plays, it then sends a command at the room position; a paused room
  sends none. A viewer catching up with nobody else watching moves the room to
  its own position instead.
- A late joiner or a replaced session does not pause the room. It syncs alone,
  and its startup counts as that viewer's stall.

### Corrections

Follow `decideRoomCatchup` in the web client:

1. Explicit seek commands always seek, immediately, outside any budget.
2. Drift within 0.35 s: do nothing.
3. A target the player can reach in the current stream: seek.
4. A pause correction that needs new media: do nothing. The next Play realigns.
5. Drift over 2 s: seek, including a re-anchor or replan.
6. Otherwise converge by rate: `1 + drift / 8 s`, clamped to 0.9–1.25x,
   tracking the room position as it advances, until within 0.35 s. Then reset
   to exactly 1x.

A Play correction whose target isn't buffered, and so needs a range request or
a stream rebuild, goes through the reload budget. Only one runs at a time. The
next waits 10 s after the previous one started playing, doubling to 60 s until
the viewer converges. An unlanded reload stops blocking after 30 s. Each
reload aims ahead by the previous load time (at most 10 s) and never past the
media duration. A quality change restarts the budget.

The correction rate is not a user speed. It is never saved or shown as a speed
change, and it always returns to 1x. Media3 may not apply speed changes with
audio passthrough, offload, or tunneling. Verify this on TV. Where speed cannot
change, use a seek inside the reload budget instead.

After 2 sustained stalls within 5 minutes, offer one step down the quality
ladder of the same file, once per quality. Withdraw the offer if no lower rung
remains or the quality changes.

### Host reports and local suspensions

A host report can move the whole room: a jump of more than 2 s or a pause
mismatch re-anchors it. The host must never report a position that isn't a
decision. These events are local suspensions, not room intent:

| Event | Where today |
| --- | --- |
| Audio focus loss or becoming noisy | Deliberately not reconciled (`PlayWhenReadyReconciliation.kt`) |
| Sleep timer | Sets `isPaused` directly (both ViewModels) |
| TV `ON_PAUSE` and `ON_STOP` | `TvPlayerScreen` pauses, then exits and leaves the room on stop |
| Phone background | Keeps playing; automatic PiP entry is not room-gated |
| Cast connect | Calls `remotePause` |
| Subtitle preparation that holds playback (Apple's subtitle AI case) | Not confirmed on Android: the subtitle remount keeps `playWhenReady`. Audit every subtitle path in M3 |
| Stall recovery (seek back, re-prepare) and the audio-sync no-op seek | Local recovery actions |
| Output-route change | Replans |

During a suspension, send no reports and no transport requests. On resume,
re-sync before reporting. While the room plays, re-attach the current session:
the server answers with a command at the projected room position (or the
waiting command) and clears this member's catching-up state. While the room is
paused, the server sends nothing; apply the snapshot anchor locally. Guests
follow the same rule. Apple asks the room to resync after subtitle AI
preparation.

### Input permissions

Route every input through one decision. Guests never seek; play and pause
follow the room policy. A denied input changes nothing locally and shows a
notice. An input during a reconnect shows an immediate notice; it is neither
dropped silently nor queued ([Apple #410](https://github.com/Silo-Server/silo-apple/issues/410)).

| Input | Today | Required |
| --- | --- | --- |
| Touch transport, gestures, scrubber, D-pad and media keys | Partly gated | Room request |
| Chapter selection (phone) | Bypasses the gate | Room seek request (host) |
| Intro and credits pill | Phone pill bypasses the gate; phone auto-skip is not pinned (TV pins it to Ask) | D8 |
| MediaSession play and pause, notification, headset, Assistant | Play and pause reconciled | Room request |
| MediaSession seek back and forward via `SeekIntervalForwardingPlayer` | Calls `seekTo` with no room check | Room seek request |
| PiP actions (`SiloPlaybackService`) and automatic PiP entry | Not room-gated | Disabled in rooms (D7) |
| SiloCast transport from a phone (`TvSiloCastReceiver`, `TvSiloCastPlayerAdapter`) | Play, pause, seek, and stop suppressed in rooms, hosts included (`TvPlayerScreen`) | Route through the TV membership's permission decision, so a host's phone can control the room |
| SiloCast `setPlaybackSpeed` | Ungated; writes the saved preference | Refuse in rooms; never write the preference |
| SiloCast launch request | Replaces the party player with solo playback | D5 prompt |
| Playback-control socket commands | Admin Stop is classified as transport and rejected | Transport follows the room; admin Stop and terminate end the local engagement (for a host, the 2-minute grace starts) |
| `PlaybackDebugReceiver` play, pause, seek | Call Media3 directly | Fault injection only; never evidence of room behavior |
| Version and edition picker (phone Quality selector, TV HUD) | Enabled; picks another file | Disabled in rooms. Quality means the ladder of the same file |
| Speed menu and hold-to-2x | Saved speed applied in rooms | Hidden in rooms; session-only 1x; restore the preference on exit |
| Autoplay, next-up, postroll | Autoplay already suppressed | Suppress; optional host Next Episode (Scope) |

### Source fallback

Request coordinated fallback only for `no_alternate_version`,
`hdr_transcode_unsupported`, `subtitle_conversion_unsupported`, and
`transcoding_disabled`, and only when the capability is advertised. Send the
failed file and selection revision, including for a startup refusal before
attach. A successful fallback changes the source and epoch for everyone.
Access and network failures never trigger fallback. If no candidate remains,
keep the original refusal. Report actual decode and output capabilities with
no blanket 1080p or SDR cap. Log the capability snapshot, including the Force
HDR Passthrough flag, with each fallback request: one under-reporting TV can
move the whole room to an SDR file.

### End of file

Match Apple (`WatchPartyEndOfFileTests.swift`): an ended player counts as ready
and paused for reports and commands. A room seek after the end remounts the
stream at the target, paused. Local seeks after the end go to the room. A
premature end remounts at the drop point without marking the item watched. Play
from the end is not sent to the room. Solo playback, next-up, and the postroll
are unchanged. Apple leaves repeated premature ends uncapped and lists that as
an open issue; Android caps the remounts and then shows the playback error.

### Other playback and teardown

- While engaged, the D5 prompt guards every solo start: detail Play, extras
  (`ItemDetailScreen`, `TvItemDetailScreen`), launcher and deep links, and
  SiloCast launch (`TvAppNavigation`). The service player is shared (`ActivePlayerHolder`), so
  nothing may start without passing that guard. Picker cards never start solo
  playback.
- Starting party playback ends a solo PiP session. Entering a party while
  casting asks the user to stop casting first.
- Teardown is idempotent across Stop, End, Leave, replacement, identity switch,
  navigation, and process recovery. Late player callbacks cannot affect the
  next solo or room session. Disposing a screen binding does not leave the
  room.

## Experience

### Entry and exposure

- Add the Experimental section to phone Settings and TV Settings (D1). Store
  the toggle locally on the device and exclude it from settings sync, like
  `player.force_hdr_passthrough`. Inject the effective policy at the platform
  boundary, the way both DI modules inject `SiloClientBuildIdentity`. Replace
  the `CLIENT_WATCH_TOGETHER_SURFACE_ENABLED` constant at its eight production
  call sites (`MainScreen`, `ItemDetailScreen`, `TvMainShell`,
  `TvItemDetailScreen`). Gate
  on build type, not the APK file name: the published `*-debug.apk` is a
  release build.
- Show entry points for movies, episodes, and series (resolved to the next-up
  episode). The TV detail screen currently offers entry for any non-audiobook
  type; restrict it. Stage the file of the displayed edition, and show the
  selected edition in the lobby.
- The hub offers Host, Join by code, Paste invitation, and Rejoin recent party,
  plus availability states: unsupported, not allowed, and failed probe with
  Retry. Hosting defaults to Host Picks; the menu creates Vote rooms today.
- Normalize codes the same way on both platforms: trim, remove whitespace and
  dashes, uppercase, and check for 8 characters from the server alphabet.

### Lobby, suggestions, and picker

- Pass a title preview from the detail page or picker so the lobby lays out
  once. Start stays disabled until the room confirms the selection. The host's
  seat appears after the socket connects; show a placeholder until then.
- Lobby Ready is advisory and counts connected guests. It is separate from
  playback readiness. The host may start alone and before everyone is ready.
  Promotion starts playback directly with no extra ready step.
- Rank suggestions by the server rule (votes, then creation time), independent
  of HTTP pagination order. Drain pages under one captured authority, merge
  without truncation, and handle more than 50 items. Treat personal votes as
  unknown until an HTTP read, and keep known flags across socket broadcasts.
  Reconcile after entry, reconnect, a vote mutation, or an uncertain outcome.
  A successful mutation followed by a failed list read is still a success with
  stale display state.
- The picker uses server rows. Continue Together is empty unless two or more
  connected members have progress; hide empty rows. The phone may add the
  chooser's own Continue Watching row, as iOS does; TV follows tvOS and web and
  leaves personal rows out. Search is debounced and cancels stale responses.
  Series flow to seasons and episodes, and an episode offers "See all
  episodes". Fetch member state only for the selected candidate; treat omitted
  IDs as unavailable, not unplayed. Watchlist and progress rows do not prove
  every member can play an item.

### During playback

- Back opens the party panel over the retained player: a phone sheet or a TV
  overlay. Everyone sees Leave and Return to playback; the host also sees Stop
  and End (D2). Closing the panel never tears down the player.
- Status, matching the web client:
  - "Syncing playback · Waiting for …" names the members the room waits for.
  - "Catching up to the party" and "The party kept playing" for the viewer who
    is catching up.
  - "Continuing without …" for everyone else when the room leaves someone
    behind (`roomMembersLeftBehind`).
  - "The host stopped playback" on Stop.
  - A host-disconnected notice when `host_connected` is false.
  - A reconnect notice after 2 s disconnected, timed from the first
    non-connected state and cleared on reconnect.
  - Immediate notices for denied actions and taps during a reconnect. Repeated
    notices show again.
  - The lower-quality offer.
- Keep focus stable on TV through live snapshots, vote reordering, and loading,
  error, and empty states. Keep pending buttons focusable while disabled. Live
  updates must not trigger a TalkBack announcement each time.

### Invitations and recent parties

- Build the host's invitation link by resolving `invite_path` against the
  verified server URL, base path included. Guests share the code only.
- The paste parser and deep-link handler accept a bare code, a server
  invitation link, and a `silo://watch-party` link (D4). Validate the app link
  as strictly as Apple #412 does: an `http` or `https` server with no userinfo,
  query, or fragment, and exactly one non-empty `server` and `token`. Scheme
  matching ignores case. Reject `silo://invite`, which is the account
  invitation link. Add a `watch-party` host to the phone manifest's `silo`
  intent filter. TV queues deep links without identity scoping today;
  port the phone's `ExternalRouteScope` model before TV accepts invitation
  links.
- Match the link's server against the current one with `serverIdsMatch`. A
  foreign server goes through the normal server, sign-in, and profile flow and
  never reuses current credentials. A LAN URL and a public URL for the same
  server count as different.
- The join token is a secret. Keep it out of route strings, saved state
  (`consumedExternalRoute`), logs, and the recent-party store.
- The TV QR code encodes the invitation link, not the bare code.
- Store one recent party in `SecureSharedPrefs` for at most 24 hours, keyed by
  server ID, `loginId`, and profile. Store the code and display metadata only,
  never a room token. Rejoin joins again by code for fresh proof. Remove the
  entry on a terminal state, on expiry, on sign-out, on server removal, and on
  profile deletion. Never show it under another identity. A replaced
  connection keeps the entry for explicit Rejoin.

### Background and process death

| Situation | Phone | TV |
| --- | --- | --- |
| Lobby, app backgrounded | Keep the engagement while the process lives. A host away longer than the grace, with its socket closed, ends the room | Same |
| Playing, app backgrounded | Local suspension: stop playback, send no reports. Resync on return | `ON_STOP` becomes a local suspension; it no longer leaves the room |
| Host socket lost for more than 2 min | Room ends for everyone (`host_left`); show the reason | Same |
| Activity recreation (font scale; rotation is handled in-activity) | Engagement survives; the screen rebinds | Same |
| Process death | A restored room route opens the hub with a Rejoin prompt. The room token is memory-only, so never re-adopt silently | Same |

### Copy and documentation

Use "Watch Party" in all user-facing text. Rename the 18 existing "Watch
Together" and "Watch together" strings, including shared repository errors, the
phone share text, and `ProfileMenu`.
The app has no localization setup; this plan adds none.

## Milestones

All milestones are pending. Write focused tests with each one. M0 fixtures can
run alongside M1. M4 can run alongside M3. Phone and TV layouts can proceed
independently once M4's actions are stable. Keep commits scoped to one
concern. Do not open a pull request unless asked.

| Milestone | Scope | Depends on | Exit evidence |
| --- | --- | --- | --- |
| M0 | Decisions, environment, fixtures, harness | — | D1 signed off; pinned runtime and devices; a two-device observation run |
| M1 | Wire contract | M0 fixtures | Fixture and API tests; both apps compile |
| M2 | Room engagement owner | M1 | Lifecycle, ordering, renewal, and action tests |
| M3 | Player binding and catch-up | M1–M2 | Player-boundary tests; first live two-player checkpoint |
| M4 | Shared lobby, suggestions, picker | M2 | Action, ranking, and pagination tests |
| M5 | Native UI, gate, invitations | M3–M4 | Rendered phone and TV flows |
| M6 | Qualification matrix | M3–M5 | Matrix passes with native playback evidence |
| M7 | Cleanup and enablement | M6 | Checks green; exposure matches D1 and the evidence |

### M0: Decisions, environment, fixtures, harness

- [x] D1 agreed 2026-09-24.
- [ ] Record D2, D3, and D5–D9. Track D4 against Apple #412 and the server
  join page.
- [ ] Pin Android, server, web, and Apple revisions for each run. Confirm
  shared-dev includes `9482302b` and advertises every required capability.
  Record the image through the runtime or fleet tooling.
- [ ] Build the fixture corpus in `shared/src/commonTest/resources/watchtogether/v2/`
  with a `SOURCE` file naming the server revision and a sync script modeled on
  `scripts/sync-apiv2-fixtures.sh`. Load fixtures through the
  `ApiV2FixtureSupport` expect/actual pattern; plain `commonTest` resources are
  only on the Android unit-test classpath. Label fixtures derived from Apple's
  `WatchPartyAPITests`, `WatchPartyStateTests`, and
  `WatchPartyEndOfFileTests`.
- [ ] Register room log attributes in the diagnostics attribute registry, with
  its parity test. Debug builds throw on unregistered attributes.
- [ ] Extend the harness. `PlaybackDebugReceiver` status gains elapsed-realtime
  and wall-clock timestamps, the server-clock estimate, the source-timeline
  position, room phase, playback state and revision, connection epoch,
  committed and attached session, pending and applied command, catching-up
  state, stall count, reload-budget state, and current rate. Never expose
  tokens or tickets. Add any new receiver actions to both manifests. Add a
  two-device runner to `scripts/android-playback-test.sh` that parses JSON
  instead of the current flat `sed`.
- [ ] Build deterministic seams: fake clock, socket, player, and HTTP, with
  delayed responses, closures, bursts, reordering, failed attach and ready,
  source refusals, and clock jumps.
- [ ] Allocate participants: a separate profile per simultaneous participant,
  a reused profile only for replacement tests, and restricted profiles for
  access tests. `silo-runtime dev api` authenticates only as admin; probe
  participant capability and room behavior with participant credentials from
  local configuration, including `X-Profile-Token` for PIN profiles.
- [ ] Devices: claim phone and TV emulators through the device hub and never
  bypass another task's claim. Both apps use `org.siloserver.silo`, so each
  participant needs its own device. Debug builds are signed with each build
  host's debug keystore; install from one host or uninstall first. Don't clear
  app data as routine setup.
- [ ] Peers: the web client on shared-dev through browser automation; Apple
  simulators from Apple `main` with Experimental → Watch Party on, via the Mac
  build workflow; a two-API stack with workers from the dev-builder for
  cross-node and worker-dependent deliveries; network shaping through the
  emulator console on owned emulators, or a proxy.
- [ ] Each run gets a unique room, an artifact directory, and a participant
  set. Cleanup closes only rooms the test created and releases only its own
  device claims, even when the test fails.

Exit: two Android clients can be observed at once with correlated timestamps,
and every run records its runtime, fixtures, and participants.

### M1: Wire contract

- [ ] Type both capability documents and the entry baseline. Keep "unsupported",
  "not allowed", and "probe failed" distinct.
- [ ] Complete the room, member, member-state, picker, and suggestion-page
  models from fixtures, including v2 string IDs and numeric socket IDs.
- [ ] Implement every operation in the table through `ownedV2Call`, validating
  the status, required fields, and IDs. A malformed 2xx is `invalid_response`.
  `safeApiV2Call` currently turns it into a network error.
- [ ] Keep room and suggestion UUIDs and exact bodies in the logical action, not
  in each network attempt. Today they are generated per attempt.
- [ ] Apply retry classes. `singleAttempt()` only skips the pinned-scope 401
  refresh; it doesn't stop OkHttp's connection retry, which is still on for the
  API client. Prove with a request-count test that non-retryable operations
  send at most once; if they don't, route them through a client with
  `retryOnConnectionFailure(false)`, as the player's auxiliary call factory in
  `PlayerOkHttpClient` does. Retry-safe
  operations, including the ticket mint, get one refresh and resend on 401.
- [ ] Adopt `room_access_token` from every 200 room response without changing
  membership. Read `exp` from the token.
- [ ] Decode `connection_replaced` as terminal. Add `lobby_ready`,
  `ready.command_id`, `state_report.command_id` and `is_ready`, and
  `self_ignore_wait`. Ignore unknown frame types; reconcile after malformed
  known frames.
- [ ] Validate tickets: `expires_in` from 0 to 30, `max_connection_seconds`
  from 1 to 300, and header-safe characters. Handle 0 as in the errors table.
  The events client's `validTicket` rejects 0, so adapt it rather than reuse it
  unchanged. Check the negotiated protocol. Mint a fresh ticket for each
  attempt.
- [ ] Add nullable `allowAlternateVersions` to `PlaybackStartRequestV3`, its
  wire body (`allow_alternate_versions`), start allocation, and `StartParams`.
- [ ] Add the error mapping table, including the 409 reconcile rule.
- [ ] Add fixture tests for re-attaching the same session in a playing room
  and in a paused room (see Host reports and local suspensions).

Exit: fixtures cover every request and response shape, authorization header,
negative capability case, retry class, and error mapping. Both apps compile.

### M2: Room engagement owner

- [ ] Consolidate transitions and eligibility. Remove the content-present and
  member-count shortcuts (`WatchTogetherEntryPolicy`,
  `WatchTogetherLobbyViewModel`). Treat an ended snapshot as terminal even
  without `room_closed`.
- [ ] Separate membership from proof. Renew through `GET /rooms/{id}` before
  `exp`, leaving more than 5 minutes plus the ticket lifetime. If renewal fails
  terminally, end the engagement and offer Rejoin.
- [ ] Fix same-room rejoin: `RoomSession` returns early for a matching room ID
  today. A new binding replaces the old connection job, and the old socket
  closes before the new owner takes over shared state.
- [ ] Order snapshots by persisted generation and socket receipt order. Fence
  suggestion reads separately from room mutations.
- [ ] Take suggestion hydration off the socket receive path. Replace the
  unchecked `trySend` and drop-oldest flows with reliable delivery or an
  explicit recovery read. Drain terminal frames before treating closure as a
  transient failure.
- [ ] Reconnect at once after a socket that stayed up (the 5-minute rotation).
  Back off from 0.5 s to 5 s, with jitter, only for sockets that fail quickly.
  Keep trying for at least the host grace period, unless a terminal signal or
  an authority change stops it. The current budget gives up after six failures
  (about 13.5 s of backoff). Never reclaim after `connection_replaced`.
- [ ] Move clock sampling to the room socket, from socket open (see Commands
  and clock).
- [ ] Serialize conflicting room and vote actions. Capture displayed ownership
  and inputs before dispatch. Reconcile uncertain Start, Select, Promote, and
  Stop outcomes without replaying them. Expose pending state so repeated taps
  can't reorder work.
- [ ] Persist the recent party as described under Invitations and recent
  parties.

Exit: tests cover stale and equal-generation HTTP, ended without close,
replacement, same-room rejoin, proof renewal, blocked hydration, event bursts,
conflicting votes, rotation reconnect timing, and uncertain non-retryable
mutations.

### M3: Player binding and catch-up

- [ ] Room playback context through both starters and every session-replacement
  path. Paused preparation. Download and resume bypass.
- [ ] Extract attachment, reporting, scheduling, readiness, catch-up, and the
  reload budget from the two controllers into the shared binding. Keep thin
  adapters that expose observed position, completed load and seek, buffering,
  and media mount identity.
- [ ] Reports from observed state; report suppression; buffering after 2 s;
  catch-up acknowledgement.
- [ ] Correction policy with rate convergence and the reload budget. Verify
  Media3 speed behavior with passthrough and tunneling on TV.
- [ ] Local suspensions and resync. Host report guards.
- [ ] One input-permission decision for every row in the input table.
- [ ] 1x session speed; speed UI hidden in rooms; preference restored on exit.
- [ ] Version and edition pickers disabled in rooms.
- [ ] Coordinated fallback with a capability snapshot in the log.
- [ ] End-of-file handling.
- [ ] Other-playback guard (D5), PiP, and Cast rules (D7).
- [ ] Idempotent teardown.

Exit: deterministic binding tests pass. Then, under a debug entry, two real
Android players attach separate sessions to the same file and pass pause, seek,
Stop and Start, rotation reconnect, a late join without a room pause, and one
catch-up after the deadline. Repeat the core flow with a web peer. This is the
first live playback checkpoint.

### M4: Shared lobby, suggestions, picker

- [ ] Replace the duplicated phone and TV entry, lobby, and suggestion
  ViewModels with one shared state and action owner that exposes domain
  destinations. Platform navigation builds the routes.
- [ ] Centralize eligibility for stage, start, lobby Ready, stop, end, leave,
  mode, policy, suggest, vote, remove, promote, queue, and rejoin. It depends
  on phase, role, capabilities, and pending work. Removing a suggestion matches
  both user and profile.
- [ ] Host Picks by default. Stage from a detail page with the displayed
  library and file. If creation succeeds but staging fails, keep that room and
  offer retry or leave; never create a second room silently.
- [ ] Suggestion ranking, pagination, personal votes, and Queue (D9).
- [ ] Picker rows, search, episode navigation, and member state with captured
  authority and bounded reads.
- [ ] Give both platforms the same guest policy, mode switching, errors,
  pending behavior, connection status, and rejoin semantics.

Exit: shared tests cover eligibility, ordering, ranking across pages, restricted
catalog responses, stale search, partial create-and-stage failure, and
identical phone and TV destinations.

### M5: Native UI, gate, invitations

- [ ] Experimental toggle and injected policy (D1).
- [ ] Hub, entry points, and availability states.
- [ ] Lobby, suggestions, picker, and options menu on phone and TV, built from
  existing components.
- [ ] Party panel over the retained player; the Back behavior in D2.
- [ ] In-playback status and notices.
- [ ] Invitations: phone share and copy, paste parser, TV QR, scoped deep links,
  and the leave-first message (D4, D5).
- [ ] Background and process-death behavior from the table.
- [ ] TV focus with the existing helpers (`TvControlState`,
  `tvControlSemantics`, `tvModalFocusBoundary`,
  `TvRestoreFocusOnModalDismiss`, `TvReturnTarget`). Phone
  rotation, font scale, large text, touch targets, TalkBack labels, and tablet
  layout.
- [ ] String rename.

The phone app has Robolectric but no Compose UI test dependency. Adding one
means updating the dependency lockfiles and verification metadata. Keep UI
tests to the flows this plan requires.

Exit: phone and TV complete every scoped flow through rendered UI, keep
playback behind the party panel, and recover from errors without losing focus
or creating duplicate rooms. Debug routes and real entry points call the same
actions.

### M6: Qualification

- [ ] Run the validation matrix on shared-dev with phone and TV emulators, then
  the web and Apple pairings. Run cross-node and worker-dependent cases on a
  two-API stack with workers. Record actual revisions and runtime identities.
  Another client's pass does not count for Android.
- [ ] Soak for at least 8 minutes to cover socket rotation. Test proof expiry
  with an accelerated clock in fixtures rather than waiting 24 hours.
- [ ] Investigate each failure at the boundary that owns it, add a regression
  test, and rerun the affected rows. Record unavailable cases as skipped, never
  as passed.

Exit: all required emulator and software rows pass with native playback
evidence. HDR, Dolby Vision, HDMI passthrough, and physical-remote behavior
need capable hardware before any claim. Keep unqualified routes gated.

### M7: Cleanup and enablement

- [ ] Delete duplicated controller mechanics, the old entry policies, dead
  readiness fields, and source-text tests that behavioral tests now cover.
- [ ] Remove `ClientSurfacePolicy`'s constant and replace its test with
  per-platform, per-build-type policy tests.
- [ ] Update comments and docs that describe immediate selection, a fixed
  selection mode, solo-host waiting, or readiness as buffering only. Update
  `AGENTS.md`, `README.md`, `FEATURES.md`, and `docs/README.md` to match D1.
- [ ] Run the full checks and inspect the diff for unrelated player changes,
  secrets, generated output, and legacy namespaces.
- [ ] Review against this plan and the server and Apple revisions current at
  that time. Refresh the baselines table if they moved.
- [ ] Record revision-pinned validation, limitations, and rollout
  prerequisites. Rollback: the server capability switch, and the toggle for
  testers. Active engagements must tear down safely either way.

## Validation matrix

The existing tests assert the old solo-host and content-present rules and must
change. Prefer behavior through fake clocks, sockets, and players over
source-string assertions.

| ID | Scenario | Required outcome | Evidence |
| --- | --- | --- | --- |
| T01 | Empty and staged lobby, restage, mode switch | No player before Playing; lobby Ready clears as the server dictates | Unit, phone and TV UI |
| T02 | Solo host start; guest joins a playing room | Room keeps playing; the guest syncs alone and is marked ready by a matching report | Unit, live pair |
| T03 | Host rejoins at a nonzero position | Starts at `anchor_position_seconds` as sent; no personal resume; no host report rewinds the room | Unit, live pair |
| T04 | Stop and Start the same title; a new title; a fallback revision | The new revision owns playback; old commands and sessions are inert | Unit, live pair |
| T05 | Ended snapshot without close; stale Playing afterward; `room_closed` `host_left`; idle expiry | Terminal cleanup; no resurrection or reconnect | Unit, socket fixture |
| T06 | Host drops for under 2 min, then for over 2 min | Returns within grace; otherwise the room ends; guests see the host notice | Live pair |
| T07 | Old or equal-generation HTTP after a socket update | The newer attachment, readiness, and selection survive | Unit, delayed transport |
| T08 | Joining the same room again with a new binding | Exactly one socket; the old owner exits | Unit, native rejoin |
| T09 | Five-minute rotation in an 8-minute soak | Immediate reconnect; no reconnect notice; same player session, reattached | Live soak |
| T10 | Ticket 401 at rotation; `expires_in: 0`; ticket 403, 404, 409, 422 | One refresh and a new ticket; 0 renews the expiring credential, then mints again; the others are terminal | Unit, API fixture |
| T11 | Proof renewal and adoption from responses | Membership survives renewal; terminal failure stops work | Accelerated clock |
| T12 | Same-profile replacement on the same API and on different APIs | The displaced client stops without retries; the winner continues; Rejoin works | Unit, live, cross-node |
| T13 | Room socket vs playback-control socket loss | Only the affected socket recovers; no duplicate player or false attachment | Unit, fault injection |
| T14 | Failed attach, stale echo, mid-play session replacement (quality, 404 renewal, replan) | Reports wait for the right echo; the new session reattaches; the room does not pause | Unit, native delivery |
| T15 | A second seek on the same session; a seek while already waiting | Readiness re-arms; guest within 1 s; host re-anchors within 15 s | Unit, real seeks |
| T16 | Lost or rejected `ready`; old `is_ready` snapshot | `state_report` healing; barrier released only on fresh acknowledgement | Unit, integration fixture |
| T17 | Duplicate, out-of-order, and late commands; superseded Play; missing or skewed clock | One current intent runs; late Play advanced; stale work cancelled | Clock and player tests, burst run |
| T18 | Stall under 2 s | No buffering report; local convergence | Player fixture, live shaping |
| T19 | First sustained stall | The room waits up to 10 s; the overlay names the viewer; resumes on recovery | Live shaping |
| T20 | Deadline passes | `self_ignore_wait`; `ready` in playing and paused rooms, retried every 500 ms; fresh target; no reports while unplayable | Unit, live shaping |
| T21 | Stall again within 5 min or within 1 min of the last pause; a lone viewer stalls | The room keeps playing; a lone viewer always pauses it and the room waits; a lone catching-up viewer resumes from its own position | Unit, live |
| T22 | Rate convergence; host drift of 2 s or less | 0.9–1.25x until within 0.35 s, then exactly 1x; host corrected, not re-anchored; passthrough falls back to seek; saved speed untouched | Unit, TV and phone |
| T23 | Unbuffered corrections on a slow link, including copy remux | One reload at a time; backoff from 10 s to 60 s; lead up to 10 s, capped at duration; explicit seeks bypass; no replan loop ([server #1259](https://github.com/Silo-Server/silo-server/issues/1259)) | Unit, live shaping |
| T24 | Two sustained stalls within 5 min | Offer one lower quality of the same file, once per quality | Unit, live |
| T25 | Host local suspensions (focus loss, noisy, sleep timer, TV pause, subtitle preparation, Cast attempt) | Room unaffected; the host resyncs before reporting | Unit, native |
| T26 | Guest controls under both policies on every input in the input table | Denied inputs change nothing and show a notice; permitted ones go to the room | Unit, touch, D-pad, MediaSession, SiloCast |
| T27 | Transport tap during a reconnect | Immediate notice; no silent drop; no queued replay | Unit, native |
| T28 | Saved speed, hold-to-2x, SiloCast speed, auto-skip, next-up, autoplay | The room keeps authority at 1x; preferences survive exit | Unit, native |
| T29 | Version and edition pickers | Disabled in rooms; same-file quality stays local | Native |
| T30 | Downloaded copy, active Cast, solo PiP before entry | Party streams; Cast asks to stop first; PiP ends | Phone |
| T31 | Detail Play, extras, deep link, SiloCast launch while engaged | D5 prompt; no hidden departure | Phone and TV |
| T32 | End of file: natural, premature, seek after end, Play from end | Apple rules; premature end remounts without marking watched; remounts capped | Unit, live |
| T33 | Direct and HLS participants; nonzero origin; window-edge correction | One source; correct clock mapping; no replan loop | Live delivery matrix |
| T34 | Approved refusal with Force HDR on and off; output-route change; stale or failed fallback; access or network failure | Only approved reasons change the room source; no local alternate | API and player tests, real refusal |
| T35 | Suggestions in both modes: vote, unvote, more than 50, removal, promotion, Queue, failed hydration after a receipt | Correct ranking and personal flags; Queue stages "Up next"; no resubmission | Unit, native, cross-node |
| T36 | Restricted picker and member state; stale search; Continue Together alone | No access leakage; bounded reads; omitted IDs not shown as unplayed; empty rows hidden | API, native restricted profile |
| T37 | Uncertain mutations, 401, malformed 2xx, 409 reconcile, 429 | Stable ID and body where replay is allowed; non-retryable operations sent at most once | Request-count and body assertions |
| T38 | Profile, PIN, account, or server change during each async stage; PIN re-unlock | Old results never publish into the new authority; re-unlock keeps the recent party | Unit, native |
| T39 | Invitation with a base path; foreign server; code normalization; invitation while engaged; TV QR scan | Correct authority; no credential reuse; leave-first message; the token never appears in logs or saved state | Unit, phone and TV UI |
| T40 | Party panel, Back, Stop, End, Leave, and focus during live updates; rotation, font scale, TalkBack | Player kept where intended; complete teardown otherwise | Phone and TV UI, recordings |
| T41 | Background and foreground per the table; process death | Behavior matches the table; Rejoin prompt after death; no duplicate owner | Emulators |
| T42 | Experimental toggle, as guest and as host | Off hides entries, ignores invitations, and leaves the party (a host's room ends two minutes later); release defaults off, debug defaults on | Unit, native |
| T43 | Solo playback after leaving | Downloads, resume, speed, tracks, Cast, PiP, autoplay, next-up, and intro preferences behave normally | Focused regression runs |
| T44 | Large seek on a copy-remux delivery | Lands within tolerance or the host re-anchors; barrier duration recorded ([server #1345](https://github.com/Silo-Server/silo-server/pull/1345)) | Live |

Run these pairings with distinct profiles:

| Host | Guest | Purpose |
| --- | --- | --- |
| Android phone | Android TV | Core native flow |
| Android TV | Android phone | Reverse authority and TV controls |
| Android phone | Second Android phone | Same form factor |
| Web | Android phone, then TV | Reference host |
| Android phone, then TV | Web | Reference guest |
| Apple tvOS | Android phone | Cross-platform native |
| Android TV | Apple iOS | Cross-platform native |
| Android phone | Apple tvOS | Cross-platform native |
| Apple iOS | Android TV | Cross-platform native |

Run core start, pause, seek, Stop, restart, and leave on every pairing. Run the
full recovery and catch-up rows on the phone and TV pair, plus representative
web and Apple rows. Add pairings when a difference or failure calls for them.
One three-viewer run (phone, TV, web) covers member changes, stragglers, and
left-behind notices. A scripted socket peer is useful for faults but does not
prove media decoded. At least two real players must advance in native and
interoperability evidence.

Sync target for a stable fixture: at most 1 s spread between clients after a
5-second settling period, with no persistent pause mismatch or growing drift.
Measure from harness timestamps and record the sampling uncertainty; samples
too far apart are inconclusive. This is a project target, not a server SLA.
Record barrier duration, replan counts, reloads, and rate episodes. A healthy
cached seek that waits out the 10-second deadline is a failure.

## Running the checks

Use Java 21 and one Android SDK path. Run filtered suites during development and
the full set at the final gate:

```sh
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest :androidTvApp:testDebugUnitTest
./gradlew :android-shared:lintDebug :androidApp:lintDebug :androidTvApp:lintDebug \
  :androidApp:lintVitalRelease :androidTvApp:lintVitalRelease
./gradlew :androidApp:assembleDebug :androidTvApp:assembleDebug
```

CI treats `NewApi` and `InlinedApi` lint findings as fatal. Fix new findings
instead of regenerating the baseline.

Runtime probes use the `silo-runtime` helper. It authenticates as admin, so use
it for health, identity, and room capability checks, and use participant
credentials for playback capabilities and room behavior:

```sh
~/.agents/skills/silo-runtime/scripts/silo-runtime dev doctor
~/.agents/skills/silo-runtime/scripts/silo-runtime dev api /api/v2/system/info
~/.agents/skills/silo-runtime/scripts/silo-runtime dev api /api/v2/watch-together/capabilities
```

Device observation after installing the matching APK on each claimed device:

```sh
scripts/android-playback-test.sh -s "$WATCH_PARTY_PHONE_SERIAL" status
scripts/android-playback-test.sh -s "$WATCH_PARTY_TV_SERIAL" status
```

Fill the serials from current device-hub discovery. M0 adds the party fields
and the two-device runner; do not document commands before they exist. Use
`--quality original` for direct, HDR, and passthrough fixtures. Emulator
decoding is not evidence of HDR output or audio passthrough.

Keep a run ledger outside tracked source: scenario ID, UTC times, Android, peer,
and server revisions with dirty state, device, API level and ABI, runtime image
and topology, fixture and delivery, roles, room, revision, session, and command
correlation, result, sampling uncertainty, and captures. Keep deployment URLs,
credentials, fixture IDs, and device IDs in protected local configuration.
Summarize durable results here as milestones finish; never commit raw logs or
captures.

## Dependencies and open items

| Item | Effect on Android |
| --- | --- |
| [Server #1307](https://github.com/Silo-Server/silo-server/issues/1307) (open) | Its acceptance needs Android, Apple, and web readiness evidence against the buffering policy. M6 supplies Android's |
| [Server #1345](https://github.com/Silo-Server/silo-server/pull/1345) (open) | Web-only pre-roll fix for copy-remux seeks. Android checks Media3's landing behavior in T44 |
| [Server #1259](https://github.com/Silo-Server/silo-server/issues/1259) (open) | Replan storms on copy remux. The reload budget is Android's side; T23 proves it |
| [Server #1284](https://github.com/Silo-Server/silo-server/issues/1284) (open tracker) | Suggestion broadcast and replacement fixes are merged (#1286, #1287). Confirm they are deployed on every API that Android testing uses |
| [Apple #412](https://github.com/Silo-Server/silo-apple/pull/412) (open), for [#409](https://github.com/Silo-Server/silo-apple/issues/409) | Proposes the `silo://watch-party` app link that Android adopts (D4). The server still needs a public join page that offers the link; the web join page currently requires sign-in. Freeze the format before M5's link handling merges; tapping a shared link end to end (T39) needs the server page |
| [Apple #410](https://github.com/Silo-Server/silo-apple/issues/410) | Android uses an immediate notice on taps during reconnect (T27) |
| [Apple #411](https://github.com/Silo-Server/silo-apple/issues/411) | Android keys recent parties by `loginId` from sign-in. Confirm that sessions from older app versions carry it |
| [Server #1379](https://github.com/Silo-Server/silo-server/pull/1379) (merged) | Room-driven starts omit `first_frame_ms`; Rejoin is a viewer start. Android's general first-frame timing, which starts at plan adoption, is separate work |
| Picker `overlay_summary` | Server picker cards carry no overlay summary, so the watchlist row shows no quality badges until the server fills it |
| Access-token lifetime | Not confirmed. If it is shorter than 5 minutes, sockets rotate earlier; T09 and T10 measure it |

## Completion record

- [x] 2026-09-24: reviewed the September 21 draft against server `2812ac39`,
  Apple `5d9dc764`, the web client, and Android `03ac5b71`; wrote this plan.
- [ ] M0: decisions recorded, environment and harness ready.
- [ ] M1–M2: wire contract and room engagement owner complete.
- [ ] M3: first live two-player checkpoint passes.
- [ ] M4–M5: phone and TV experiences pass rendered checks.
- [ ] M6: emulator, software, and interoperability matrix passes.
- [ ] Hardware and deployment gates recorded and met for each route being
  enabled.
- [ ] M7: cleanup, final checks, and enablement per D1.
