# Android notification inbox on API v2

Phone and TV use the v2 notification inbox, capabilities and preference routes.
REST requests capture the acting viewer, and the repository discards responses
from a previous viewer. Its identity-transition gate clears cached rows and counts
before account, server, profile or temporary-auth changes become visible.

The list retains its signed `read_cutoff`. Mark all read captures that exact
cutoff for the user action and sends it once. It does not optimistically mark
cached rows read. After a confirmed mutation, the inbox rereads authoritative
rows and counts. A concurrent new delivery therefore remains unread. Uncertain
writes display an error; retries require a new user action. A cutoff realtime
event triggers an authoritative refresh instead of comparing rounded timestamps.

Forward sync stores `sync_cursor` even on empty and final pages. Checkpoints use
the saved login incarnation, server origin and profile and live in app-private
storage excluded from backup. Sync retains its fixed page size and handles up to
20 pages per wake, saving each checkpoint. A later wake continues a larger
backlog. Historical browsing uses the list cursor separately. A stale list page
cannot replace a cursor installed by a newer refresh.

Library IDs remain strings, including opaque IDs. The retained websocket bridge
can still decode its legacy integer library IDs. Event tickets and push
registration retain their existing transports; this change does not send test
notifications or enroll devices.

Focused checks cover fixed-cutoff read-all with a concurrent new delivery,
uncertain writes, saved final checkpoints, login isolation, stale profile replies,
pre-transition state clearing and cutoff event decoding. Phone and TV compile
and their formatter tests pass. Live websocket/push delivery and device UI
behavior still require separate synthetic validation. Server inbox ratification
and the migration's accountable-owner gate remain separate acceptance steps.
