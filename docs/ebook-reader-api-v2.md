# Android ebook reader state on API v2

The phone reader uses v2 ordered progress and guarded reader configuration.
Ebooks remain excluded from Android TV navigation; its shared data bindings
compile with the same transport. Binary reads and annotations use their v2 routes.
`EbookReaderApi` and `EbookReaderRepository` delegate only to `EbookReaderV2Api`
(capability, progress, reader config and annotations in one class); there is no
v1 fallback. Downloads likewise go only through the v2 registry and creation APIs.

Page and locator changes capture the event time before asynchronous work starts.
The existing Room transaction stores that time with the saved login incarnation,
origin, file, location and progress. Retry sends the same timestamp and body.
The server orders timestamps rather than taking the furthest position, so a
newer backward move can win. Older events cannot coalesce away newer pending
positions, including when changing file versions. Missing success receipts remain
queued. File IDs are strings on the wire and checked at the integer renderer
boundary.

Replay requires the original saved login, server origin and profile. A temporary
authentication overlay pauses replay. Records without provable login ownership,
including old payloads, remain quarantined instead of being sent under whichever
account is active. This change does not migrate or enroll existing users. Client
clock errors remain subject to the server's rejection of future event times.

Each opened reader captures one configuration scope and ETag. Saves preserve
other client keys and replace the `android_reader` display-settings object under
`If-Match`. Successful writes install the returned ETag for the next serialized
save. Conflict, cancellation or uncertain replies clear the writable validator;
local settings remain and a notice asks the user to reopen the reader to reload
server state. The identity generation guard prevents a delayed local settings
write from crossing an account or profile transition. Unused v1 configuration
write wrappers have been removed.

Focused tests cover timestamp retention, backward positions, empty and invalid
responses, file identity, scoped validators, conflict fencing, other-client
configuration preservation, competing login and overlay behavior, and Room
coalescing. Phone and TV Kotlin compilation passes. No live server/device reader,
physical process-kill, file download or annotation validation is claimed here.

Remote ebook files use the captured saved login and profile throughout the PDF,
comic and reflowable readers. Requests retain that scope through media-token
refresh and refuse a changed identity before dispatch or retry. Cache names
include the login incarnation and profile; old URL-only cache entries are not
adopted. The loader checks identity before returning a cached or downloaded file.

The full-file loader accepts HTTP 200 and does not follow redirects. It retains
the existing 2 GiB declared/streamed size limit, temporary-file cleanup and Kindle
conversion-failure handling. A partial response cannot be cached as a full book.
Local files and content URIs keep their existing paths. This consumer does not
issue HEAD, conditional or range requests; server support for those operations
is not native test evidence.

Annotations drain bounded 50-row pages before publishing a complete server list.
Repeated or malformed continuations and foreign content identities fail the load;
partial server lists are not published as complete. The existing bookmark UI uses
client-selected IDs persisted with login/origin ownership before creating remotely.
Reopening retries pending creates with the same ID and location, accepting the
server's current winner. Legacy local bookmarks and another login's pending work
are never uploaded automatically.

Deletes retain the fetched ETag and persist a deletion record before dispatch.
Recovery sends that guarded delete, never a create for a pending deletion. A 404
confirms absence; conflicts retain the bookmark and ask for a reload. Local cleanup
occurs after acknowledgement. The shared PATCH adapter preserves omitted fields
and explicit nulls and requires an annotation validator; no new annotation-edit UI
is exposed. Reader operations serialize locally and check captured identity before
publishing results or changing local state.
