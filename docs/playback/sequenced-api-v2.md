# Sequenced playback on Android

Phone, TV and audiobook playback negotiate `/api/v2/playback/capabilities` before
starting. Servers without `sequenced_progress_v1` retain the existing v1 path,
including temporary cast authentication. Capability failures do not downgrade a
configured request. Configured playback requires a saved login, authenticated
profile and installation ID.

The shared repository owns the protocol so staged candidates, cast sessions and
orphan cleanup use the same stop path. Start bodies are persisted before sending.
Progress samples receive increasing sequences; an uncertain sample retries with
its original position and paused state before a newer sample is allocated.
Backward seeks can therefore supersede older positions.

Stops persist one UUID and body before dispatch. Only a matching 200 stopped or
replayed receipt confirms completion. Draining, network errors and 503 responses
retain the request after three attempts. Settings exposes **Retry pending
playback stops**. Recovery validates the installation, canonical account, saved
login and profile, resolves an uncertain start with its original attempt, and
stops it without starting a renderer. It does not stop a currently adopted
in-process player. Identity changes fence pending requests; stored requests grant
no authority.

The app-private journal uses atomic writes and a process ownership lock and is
excluded from Android backup. It stores request identities and bodies without
credentials. Clearing application storage removes this recovery state. Validation
errors, including 422, retain uncertain starts: the current contract does not
prove absence of an earlier allocation. Such requests can remain pending until
the server can resolve the attempt.

Negotiated sessions do not replan, emit legacy route events, trigger outage
replacement, or write progress through the personal-data outbox. Local renderer
retirement is distinct from a confirmed server stop. File IDs remain JSON strings
in requests and the journal; the existing renderer accepts only canonical
positive IDs representable by its integer model and refuses other IDs.

Focused verification covers persisted requests, lost progress replies, backward
samples, draining stops, mismatched receipts, login changes, absent-feature
fallback and shared lifecycle behavior. Phone and TV compilation covers their
caller and Settings wiring. Device playback, process-kill durability and live
server integration require separate synthetic validation. This change does not
enable server enrollment or production playback admission.
