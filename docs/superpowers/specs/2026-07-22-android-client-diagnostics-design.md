# Android Client Diagnostics Design

**Status:** Implemented on `feat/android-client-diagnostics`, pending review
**Date:** 2026-07-22
**Repository:** `Silo-Server/silo-android`
**Delivery:** One comprehensive pull request with ordered internal commits

## Purpose

Build the Android and Android TV implementation of Silo's self-hosted client-diagnostics protocol. The implementation follows the Apple client's product behavior and the canonical server contract while using Android-native crash, exit, storage, lifecycle, and device-probe APIs.

This is a support and debugging feature, not analytics or a general telemetry platform. Reports go only to the authenticated user's own Silo server after destination-bound consent.

## Canonical contract

The implementation targets schema version 1 and the server endpoints already merged in `Silo-Server/silo-server#445`:

- `GET /api/v1/diagnostics/status`
- `POST /api/v1/diagnostics/reports`

The server repository's `docs/design/2026-07-19-client-diagnostics.md` and `docs/design/schemas/client-diagnostics/v1/` are canonical. Android copies the canonical valid and invalid fixtures into its tests and must remain byte- and behavior-compatible with the accepted archive contract.

## Scope

The single pull request delivers:

- Schema-v1 models, validation, fixtures, and API integration.
- Safe structured logging, an in-memory ring, and optional rotating debug files.
- JVM crash capture and Android `ApplicationExitInfo` collection.
- Pending-report persistence, deduplication, retention, and crash-loop controls.
- Device, display, audio, network, and decoder snapshots.
- Tar/gzip bundle construction, hashing, redaction, and authenticated upload.
- Account/server-bound consent and fail-closed profile gating.
- Phone and TV settings, prompts, review, deletion, upload, and sent history.
- One-shot manual reports and timed Start/Stop diagnostic captures.
- Curated playback, focus, network, navigation, download, cast, and lifecycle instrumentation.

The work is kept navigable through ordered commits inside the one PR. It is not split across stacked PRs.

## Non-goals

- No Sentry, GlitchTip, Crashlytics, OpenTelemetry, ACRA, or other diagnostics dependency.
- No continuous or live log streaming.
- No screenshots, recordings, analytics, fleet metrics, or advertising identifiers.
- No client-side diagnostics administration UI beyond the existing product policy.
- No symbolication service.
- No decoding of native tombstone protobufs.
- No device installation or launch as part of implementation verification unless separately requested.

## Module boundaries

### `shared/commonMain`

Owns platform-neutral wire models, schema validation helpers, `DiagnosticsApi`, multipart request construction, upload response/error mapping, and canonical fixture tests. Upload uses a diagnostics-specific result so stable error codes and `Retry-After` survive Ktor response handling.

### `android-shared`

Owns Android-native diagnostics infrastructure under:

`android-shared/src/androidMain/kotlin/org/siloserver/silo/common/diagnostics/`

This package contains structured logging, redaction, ring/file storage, consent state, identity resolution, crash and exit capture, pending reports, device snapshots, bundle construction, upload orchestration, and lifecycle coordination. Framework-facing code is hidden behind narrow interfaces so core behavior can be unit-tested.

### `androidApp`

Owns phone Compose settings, report prompts and review screens, timed-capture controls, navigation integration, and `SiloApplication` startup wiring.

### `androidTvApp`

Owns remote-first TV settings, full-screen prompt/review flows, timed-capture controls, focus behavior, and `SiloTvApplication` startup wiring.

## Runtime data flow

1. Both `Application` classes install crash capture before Koin starts.
2. `SiloLog` continues forwarding to Logcat and also offers a sanitized JSONL entry to the in-memory ring.
3. If debug logging or timed manual capture is active, a single writer drains entries into bounded append-only segments.
4. A lightweight coordinator maintains the active diagnostics binding, profile eligibility, foreground state, playback-session IDs, and a pre-rendered device snapshot cache.
5. A JVM crash writes one bounded marker and chains to the previous exception handler.
6. On a later foreground launch, JVM markers and eligible `ApplicationExitInfo` records become pending reports.
7. Ask mode displays an incident prompt; Always mode schedules a guarded upload; Never purges and disables persistent capture.
8. Review builds a human-readable summary from local metadata. Nothing is uploaded merely by opening review.
9. Upload rebuilds and revalidates current identity and consent, creates the canonical bundle, rechecks identity, and posts multipart data.
10. A successful `201` deletes the local report and retains only the short report ID and send date.

## Structured logging

### `SiloLog`

`SiloLog` exposes verbose, debug, info, warning, and error functions accepting:

- A registered diagnostics category.
- A bounded tag.
- Authored free text that must not interpolate network payloads or credentials.
- Typed, registered attributes.
- An optional sanitized `Throwable`.

Every accepted line uses the canonical JSONL fields: `ts`, `run`, `lvl`, `cat`, `tag`, `msg`, and `attrs`. Categories are playback, focus, network, lifecycle, browse, cast, download, crash, and other.

Existing `Log.*` calls are migrated only where their evidence is useful and safe. Unmigrated logging remains in Logcat and is not automatically ingested.

### Redaction

Collection-time redaction:

- Removes URL user info, queries, and fragments.
- Replaces known server hosts with stable one-way tokens while leaving loopback hosts usable.
- Removes authorization values, cookies, JWTs, emails, profile/access/refresh tokens, and registered sensitive strings.
- Sanitizes exception chains without copying raw request bodies or headers.
- Rejects unregistered attributes in debug/test builds and drops them in production.

Bundle-time redaction repeats exact-token and textual-entry scanning. If a textual artifact is not valid UTF-8 or cannot be verified as redacted, that evidence is replaced with a redaction-failure sentinel rather than uploaded verbatim.

### Ring and files

- The ring is always available in memory, bounded to approximately 4,000 entries and 1.5 MiB.
- Writes are non-blocking and do not share locks with crash persistence.
- Snapshots tolerate a torn final entry and report dropped/torn entries honestly.
- Persistent debug capture uses one coroutine writer, a bounded channel, and five append-only 2 MiB segments under `noBackupFilesDir`.
- Backpressure drops entries and increments a counter rather than blocking playback.
- Server/account/profile/consent transitions rotate or clear the active generation before exposing the new identity.

## Identity, consent, and privacy

A `DiagnosticsBinding` is `(server_instance_id, account_user_id)`. Consent also records `consent_notice_version`. The capturing profile ID is attribution, not the consent scope.

`DiagnosticsIdentityResolver` establishes that binding from the authenticated status response plus `AuthRepository.getCurrentUser()`. It persists only the last positively validated binding for the active saved server, along with the active profile's last positively resolved child status. Missing, stale, mid-transition, or contradictory identity data fails closed. The cache is invalidated before sign-out, server removal, account replacement, or token invalidation becomes externally visible.

Process-only `TemporaryAuthScope` sessions do not replace the saved diagnostics binding. Entering a temporary scope closes persistent capture and rotates the log generation; leaving it resolves the saved identity again before persistent capture resumes. This prevents SiloCast or remote-playback credentials from retargeting local reports.

Rules:

- Consent modes are Ask, Always Send, and Never. Manual sends use manifest mode `manual`.
- The default is Ask. The device-wide debug-logging preference defaults off.
- Bumping the notice version demotes Always to Ask.
- Ask still permits user-enabled local debug capture, so a notice bump does not change the device-wide debug preference. Never is the only consent mode that disables and purges persistent capture.
- Reports never migrate or retarget to another server or account.
- Signing out or removing a server purges that binding's reports, persistent logs, sessions, breadcrumbs, marker ownership, status cache, and consent history as required by the canonical contract.
- Selecting Never purges the same evidence immediately and disarms persistent capture.
- Never still permits an explicit one-shot or timed manual report; it never enables background capture or automatic upload.
- Leaving authenticated state closes the capture gate before navigation or lifecycle state is published.
- Server, account, and profile changes close the old gate before the new identity becomes visible.
- Child profiles cannot manage, review, send, or persist diagnostics. A crash confirmed to have occurred under a child profile is discarded because the shipped server rejects child-profile attribution.
- If an active profile existed but its eligibility cannot yet be resolved, evidence remains quarantined and non-uploadable until resolution; confirmed child evidence is then purged and confirmed adult evidence may proceed. A crash with no active profile is valid account-scoped evidence and remains unattributed.
- Profile-generation rotation prevents child or unresolved-profile logs from entering an adult manual report.
- A timed manual capture is bound to the identity and eligible profile active at Start. Any identity/profile change stops and invalidates it.

Upload checks the active server, server instance, account, active-profile eligibility, access token, consent notice, consent mode, and server availability both before bundle construction and immediately before the request. Retained reports are account-scoped, while their captured profile remains immutable attribution. Another eligible adult profile on that account may review or send the report, and the diagnostics request uses the captured `X-Profile-Id`; an unattributed report suppresses that header. A server or account mismatch retains or purges according to its classification; it never retargets.

## JVM crash capture

`CrashCapture` is installed idempotently and records the prior `Thread.UncaughtExceptionHandler` exactly once. The handler:

- Performs no networking, coroutines, DataStore operations, service probes, Koin access, or archive construction.
- Renders a bounded stack trace and captures only the ring snapshot available within a hard time/size budget.
- Avoids regex redaction and serialization frameworks on the dying thread. A bounded exact-value replacement protects captured credentials before the app-private marker is written; structural redaction runs during next-launch report assembly.
- Uses pre-rendered identity, foreground, playback-session, and device-snapshot state maintained during normal execution.
- Writes one marker no larger than 512 KiB using a temporary file and atomic rename where supported.
- Always calls the previous handler in `finally`.

Marker conversion and bundle construction occur only after restart.

## Android process-exit evidence

On API 30+, an `ExitInfoCollector` reads `ActivityManager.getHistoricalProcessExitReasons()` through an injectable adapter.

Each process run receives an opaque run-correlation token. Once identity is resolved, a bounded local run ledger maps that token to the validated binding, profile, eligibility, process start, and capture session. On API 30+, the opaque token—not server, account, profile, or credential data—is also published through `ActivityManager.setProcessStateSummary()`. `ApplicationExitInfo.processStateSummary` correlates an exit record back to the ledger. An exit without a matching validated ledger entry is not turned into an uploadable report.

- `REASON_CRASH` becomes `crash`.
- `REASON_ANR` becomes `anr`.
- `REASON_CRASH_NATIVE` becomes `native_crash`.
- Optional traces are read with strict byte limits.
- ANR/JVM trace text becomes `crash/stack.txt`.
- API 31+ native tombstones become opaque `crash/tombstone.pb`.
- No tombstone content is placed in the manifest.
- A two-segment, 128 KiB-per-segment journal retains only explicit, already-redacted lifecycle breadcrumbs. Opaque segment ownership hashes bind lines to server/account/profile/generation as well as capture-session ID, so a process death during asynchronous rotation cannot cross-attribute stale evidence. Identity gates rotate the journal and Never/sign-out purge it.

Deduplication uses a bounded persisted fingerprint derived from process name, PID, timestamp, reason, status, and trace hash. JVM markers participate in the same deduplication so a death produces one report. Fingerprints are recorded only after the pending report survives validation and retention enforcement.

## Device snapshot

`DeviceSnapshotCollector` composes existing probes with new read-only accessors for:

- Manufacturer, model, `Build.DEVICE`, form factor, OS, app version/build, and a one-way build-fingerprint hash.
- Current and supported display modes, HDR types, and wide-color support.
- Current audio outputs, channel/encoding capabilities, passthrough formats, and active suppressions.
- Hardware/software video decoders and relevant profile/capability limits.
- Current network transport without addresses or SSIDs.

Values are explicit, `unknown`, or `not_collected`. A cached pre-failure snapshot is updated outside crash handling and stored with crash markers. Manual reports capture a fresh pre-failure snapshot. Post-restart evidence is labeled honestly.

## Pending reports

Reports live in app-private no-backup storage using staging directories and atomic publication. Each directory contains binding metadata, state, manifest draft, device snapshot, frozen logs, and allowlisted evidence files.

- Maximum three pending reports per binding.
- Seven-day expiry.
- Oldest reports are removed first, except a newly captured report is not marked seen until it is confirmed to survive cap enforcement.
- Failed staging is removed completely.
- Pending reports are uploadable only to their original binding.
- Prompt suppression, seen fingerprints, and auto-upload throttles are bounded and pruned.

## Bundle and upload

The multipart request contains exactly two parts in order:

1. `manifest`: `application/json`.
2. `bundle`: `application/gzip`, filename `bundle.tar.gz`.

The tar allowlist and order are:

1. `manifest.json`
2. `device.json`
3. `logs.jsonl`
4. `crash/summary.json`, when applicable
5. `crash/stack.txt`, when present
6. `crash/tombstone.pb`, when present
7. `breadcrumbs.jsonl`, when present

The embedded manifest omits the archive object. The external manifest contains finalized gzip size, uncompressed tar-stream size, entry list, and SHA-256 of transmitted bundle bytes.

Upload outcomes:

- Success: delete local evidence and record short ID/date.
- Offline, HTTP 429, server 5xx, busy, or quota exceeded: retain with bounded retry/backoff; persist and honor server `Retry-After` per account binding.
- Too large: retain and mark locally unsendable.
- Disabled or storage unavailable: retain without retry storms.
- Unsupported schema: retain and show Server update required.
- Stale consent: refresh notice/mode or require re-consent.
- Destination/profile/account mismatch: never send and never retarget.
- Invalid/archive mismatch: retain as a permanent local failure for inspection/deletion.

Automatic uploads use WorkManager network constraints but still perform all identity and consent checks at execution time. Foreground sends use the same uploader.

## Phone and TV UX

Diagnostics is visible in Settings only to non-child profiles. For eligible profiles it remains visible when uploads are unavailable so users can inspect and delete local reports. The last positively validated binding is cached so retained reports and sent history remain visible while that same profile goes offline; every profile transition clears the cache until the new profile is positively verified. Status copy distinguishes Available, Disabled by server, Storage unavailable, and Offline.

Settings include:

- Crash Reports: Ask, Always Send, Never.
- Debug Logging toggle.
- Pending reports with type, time, size, destination, and expiry.
- Sent report history containing only short ID and send date.
- Send diagnostics now.
- Start diagnostic capture.

Ask mode presents one aggregate incident prompt for eligible pending reports, and each prompt action applies to the displayed batch. Review shows incident type/time, app and device identity, evidence categories, log counts and size, destination server, captured profile, expiry, and exact archive entries. Always Send requires a second confirmation.

Phone uses Compose settings, sheets, and detail screens. TV uses full-screen, remote-friendly screens. The TV incident prompt focuses Don't send by default.

### Timed manual capture

Start diagnostic capture creates an isolated persistent generation bound to the current server/account/profile, displays a persistent in-app indicator, and records verbose diagnostics. Stop freezes the generation and opens review. Cancel deletes it. Identity or eligibility changes invalidate the generation rather than transferring it.

Send diagnostics now creates a one-shot manual report from the current matching ring generation and warns when only basic in-memory evidence is available.

## Instrumentation

Instrumentation is curated and contract-safe:

- Playback analytics events and five-second stats snapshots while debug/timed capture is active.
- TV focus transitions and actions without view text or media titles.
- Network method, templated route, status, duration, and coarse failure class only.
- App foreground/background, navigation route identifiers, server/profile generation changes, downloads, cast state, and crash lifecycle.
- Playback-session IDs for server log correlation, scoped to the active binding.

Media titles, subtitle text, request/response bodies, credentials, raw URLs, and arbitrary exception messages are excluded.

## Error containment

Diagnostics must never make application startup, authentication, browsing, playback, downloads, or shutdown fail. Non-crash initialization is guarded and reports internal failures to Logcat. Storage and upload errors become explicit local states. The crash handler always delegates to the previous handler.

## Testing and acceptance

Implementation follows test-driven development for every behavior-changing unit.

Required coverage:

- Canonical valid and invalid contract fixtures.
- Leak fixtures for JWTs, tokens, signed URLs, cookies, emails, known hosts, and malformed UTF-8.
- Ring ordering, capacity, concurrency, torn entries, and drop counts.
- File rotation, bounded channels, and generation isolation.
- Crash-marker bounds, atomic publication, hard-budget behavior, and prior-handler chaining.
- Exit reason classification, bounded trace reads, fingerprints, and JVM/exit-info deduplication.
- Pending staging, expiry, cap behavior, binding isolation, sign-out/remove/Never purges, and fingerprint timing.
- Device snapshot tri-state behavior through fake framework adapters.
- Deterministic tar order, gzip, sizes, external/embedded manifest relationship, and SHA-256.
- Multipart order, status parsing, server error mapping, throttling, and retry state.
- Identity/profile/consent changes before and during bundle construction and upload.
- Child-profile gating and timed-capture invalidation.
- Phone and TV presentation state, report actions, and TV default focus.
- Startup installation through focused Robolectric tests.
- Logging hot-path performance regression coverage.

Final verification runs shared tests, Android unit tests, both application Kotlin compilations, and both debug APK builds. Device installation or launch is excluded unless separately requested.

## Success criteria

The feature is complete when:

- A JVM crash and an API-30+ exit record each produce one correctly classified pending report.
- Ask, Always, Never, manual one-shot, and timed capture work on phone and TV.
- No report can cross a server, account, or profile eligibility boundary.
- Child and Never transitions cannot leak old evidence into later manual reports.
- Bundles pass canonical schema fixtures and server-side validation.
- Existing crash handlers are preserved.
- Playback remains unaffected when persistent debug logging is off.
- The complete feature is delivered in one reviewable PR without observability SDK dependencies.
