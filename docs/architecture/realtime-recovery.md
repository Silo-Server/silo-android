# Realtime work recovery

Android Home and notifications share the event selection transport. Watch Party
uses the shared room repository and player binding on phone and TV. The existing
experimental Watch Party setting still controls entry.

The optional `work_admission` object on a v2 ticket selects recovery version 1.
Events can also advertise it on hello; room capabilities carry the same object.
The client retains the reported backend, scope and enforcement epoch without
assuming that a memory backend is shared across server nodes. Missing or unknown
versions keep legacy action delivery and do not enable structured work retries.

An event connection sends its initial selection in one subscribe frame, with no
URL declaration. The URL builder still supports declarations for read-only
observers. Selection requests carry an ID; only the current ID can acknowledge
or reschedule the latest desired selection. A refusal retains accepted state.
Changing the desired selection cancels the old timer but preserves its minimum
retry time. The logical event transport retains that monotonic deadline across
physical socket replacement and checks it before minting another ticket. A
change of authenticated authority clears that deadline.

An automatic room transport retry requires a recognized, correlated error with
`work_started: false` and a retry delay. It may run once within five seconds on
the same room, selection revision, generation, connection epoch/ID, playback
session, command and authority. Request IDs correlate results; they do not make
operations idempotent. A missing acknowledgement, failed send, timeout,
downstream error or reconnect after dispatch is uncertain delivery. A known
pre-work refusal, including a retry selected but never dispatched, remains
undelivered. Selection does not reset the original five-second lifetime; the
repository rechecks expiry and identity at the final client dispatch boundary.
For uncertain delivery, reconcile the room, explain the uncertainty and let the
viewer decide whether to issue a new intent.

V2 `action_result` acknowledges `transport_request` or `lobby_ready` only when
its request ID, action and `applied` status match. Existing operation snapshots
and commands arrive before this acknowledgement. A matching transport result is
therefore accepted after the operation changes the room generation. Lobby
acknowledgements and snapshot echoes require the current authority; leaving the
lobby cancels its pending readiness.

Attachment, readiness, buffering and reports are recomputed from the current
player observation. Class retry floors delay them without retaining stale
positions or merging command/session/selection identities. Cancelled desires
retain at most 128 request correlations for their original five-second response
window so delayed refusals still impose the class floor, without reviving stale
retries. The recovery-class floor also delays room reconnect and ticket minting.
A buffering send is in flight until an authoritative echo; correlated refusal makes the current
stall eligible again. Recovery cancels the buffering desire. The binding keeps
the existing ready plus report pair at the waiting cadence. Absolute seek input
keeps the latest target in each 250 ms window and displays it immediately;
a distinct play or pause flushes the queued seek first and ends that window.
Play and pause remain distinct operations. Local seek intent tokens ensure that
an older timeout or refusal cannot clear a newer target. A playback binding
lease is revoked synchronously on disposal; retries also require its original
player session to remain current. Revocation preserves room membership.

Ticket 429/503 responses preserve structured retry timing and `Retry-After`.
Retries add 0–250 ms jitter. Unavailability backs off through 2/4/8/16/30 seconds;
opaque failures use 1/2/4/8/16/30 seconds. Only a 1008 close with reason
`realtime input shield`, or its `realtime_input_limited` notice, selects the
5/10/20/30-second shield sequence. Opening a socket does not clear overload
backoff; it takes 30 seconds of useful accepted activity. A long-lived socket
cannot use immediate renewal to bypass this delay.

The focused tests use fake time, stub APIs and mock sockets. They establish
client state transitions and wire handling, not rendered UI recovery, real
playback, server admission enforcement or capacity.
