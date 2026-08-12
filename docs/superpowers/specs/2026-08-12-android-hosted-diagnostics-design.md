# Android Hosted Diagnostics Design

## Goal

Offer a Silo-operated diagnostics destination on phone and TV without adding a
third-party observability SDK or weakening the existing local review, consent,
identity, retention, and erasure boundaries. Hosted is the device default;
self-hosted ingest remains selectable for deployments that operate it.

## Destination policy

Hosted reports target `diagnostics.siloserver.org` and the compile-time collector
identity `silo-public-diagnostics-v1`. Hosted consent is manual/Ask-only: the
client never turns a hosted report into an unattended crash upload. The hosted
collector advertises schema, size, notice, and 30-day remote-retention policy.
Local unsent evidence retains the existing seven-day limit.

Self-hosted reports retain the originating server's status, account binding,
profile attribution, consent modes, and upload authorization. A report's
destination is frozen at capture and cannot be changed during upload.

## Identity and capture boundary

The hosted manifest omits source server, account, and profile identifiers. Local
sidecar state binds evidence to a one-way owner derived from the active local
server ID and authenticated Silo account ID. This owner is persisted per local
server, survives ordinary access/refresh-token rotation, and is erased on
persistent account sign-out, server removal, or account replacement. Clearing a
temporary TV authentication overlay does not erase the persistent account it
was layered over.

Immediately before a manual or timed capture starts, the client fetches hosted
capabilities and requires the exact collector ID. Cached capabilities are useful
for presenting retained evidence, but cannot authorize a new live capture. The
collector check uses the public collector only; it does not send Silo account
credentials to that service.

Immediately before the first hosted create/upload, the client performs a live,
authenticated `getCurrentUser` lookup against the active Silo server. Its
one-way owner must match the report binding before transport starts and again at
the final identity check. JWT claims and the persisted owner support local/offline
display only; neither substitutes for first-upload account attestation.

## Transport lifecycle

The client persists the exact sanitized hosted envelope before create, then
replays it for ambiguous retries. A validated `processing` receipt is an accepted
remote state, not a failure: local evidence is retained, the reference is shown,
and WorkManager polls status until `ready` or rejection. Only `ready` records sent
history and removes the local report. WorkManager refreshes coordinator identity
state before each attempt and retries source-server unavailability. If the
collector rejects the active pseudonymous installation credential, the client
registers a replacement while retaining one encrypted fallback credential for
status and erasure of already-submitted reports; exact-value redaction covers
both credentials.

Local retention or quota eviction of an accepted but still-processing report
keeps only its UUID and binding as remote-erasure authority. It does not promote
the processing reference into READY history; only a validated READY response
may do that.

Remote erasure is intent-first. Local deletion persists the hosted report ID,
hides/removes local evidence, and reconciles remote DELETE asynchronously so a
slow collector cannot block diagnostics UI or identity commands. A
network-constrained WorkManager job drains these durable intents after process
death. Collector failures remain queued because an ownership-hiding response
cannot prove that the remote report was erased.

## Bundle boundaries

Privacy sanitization and manifest policy remain in `FileDiagnosticsBundleBuilder`.
Deterministic USTAR/gzip encoding and hashing live in
`DiagnosticsArchiveEncoder`, keeping archive mechanics independent of hosted
privacy admission rules. Hosted envelopes omit opaque tombstones and apply the
existing identifier, URL, device, stack, and log allowlists before encoding.

## Verification

Tests cover live collector refusal, live account refusal, stable ownership across
token rotation, owner erasure with server purge, processing-state polling,
worker retry behavior, non-blocking remote deletion, deterministic archives, and
the existing diagnostics privacy/integration contract.
