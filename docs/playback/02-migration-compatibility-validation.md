# Migration, Compatibility, and Validation

> Historical migration note: the A/B sequence below describes the completed
> Media3-only migration and the pre-neutral v3 compatibility window. The
> platform-neutral protocol is a breaking contract and has no fallback to the
> older engine-shaped request/plan model.

Status: **Release B target and dev-server v3 flow implemented; rollout remains
blocked on a published minimum server revision and the remaining Phase 0
hardware fixtures**.

The current source tree contains the Release B target (MPV behaviour and
packaging are both removed). The A/B split below remains the recommended
deployment and rollback sequence, not two coexisting runtime implementations.

This document defines implementation order, proof, rollout, and rollback for the
[target architecture](01-media3-only-player-architecture.md). It does not
redefine delivery, capability, HDR/DV, audio, subtitle, recovery, or
telemetry semantics.

## 1. Release sequence

| Stage | Artifact | Entry gate | Rollback boundary |
| --- | --- | --- | --- |
| Phase 0 | Published minimum-compatible Silo server release | May begin immediately | Before any Release A rollout, server rollback may restore the prior protocol. After A begins, roll back only to another v3-compatible build or revert A first. |
| Release A | Android MPV-disabled release; MPV remains packaged but unreachable | Minimum server is published/deployed, Phase 0 proof, Release A automated gates, and pre-A lab gates pass | Halt rollout or revert Release A. |
| Soak | Evidence and release decision | Metrics and hardware gates are measurable | Do not proceed to Release B. |
| Release B | Android MPV-removal release | Release A completes the recorded observation window within numeric budgets | Revert Release B to restore dormant packaging; Media3-only behaviour remains. |

Release A and Release B are separate pull requests and separate app releases.
Restoring functional MPV after Release B requires reverting both releases and a
new reviewed product decision; it is not an emergency runtime flag.

Once any Release A build is in use, the minimum v3 server contract is a
compatibility floor. A server rollback below that floor requires halting or
reverting Release A first.

## 2. Phase 0: Server readiness

This current-state snapshot is pinned to Android `7bb30c6a` and server
`9314df18`. The pair does not provide the contract assumed by the target
architecture:

| Current gap | Consequence |
| --- | --- |
| Android calls `/playback/decide` and session route-events, but server `internal/api/router.go` registers neither. | Playback plans and release telemetry are dead wire. |
| Server `internal/api/handlers/playback.go` does not consume Android's proposed quality, subtitle-selection, or v2 context fields. | Android inputs can be silently ignored. |
| The same handler accepts explicit direct play without full resolver validation. | Unsupported HDR/DV, audio, or subtitle combinations can be forced direct. |
| Android `PlaybackFallbackCandidate` contains only delivery, engine, and reason. | Android cannot execute the intended codecs, range transform, tracks, or stream. |
| Server `internal/playback/transcode.go` has no DV→HDR10 or HDR→SDR tone-map/color pipeline. | The documented color fallback cannot be advertised safely. |
| Server audio fallback is not output-route- and channel-aware. | Compatible video can be unnecessarily transcoded or surround audio collapsed. |
| Server resolver fallback can send unsupported original bytes when adaptation is disabled. | There is no safe terminal outcome. |
| Android analytics are local and the server has no route-event ingestion. | Cohort and rollback metrics cannot be calculated. |

### 2.1 Required server API

Phase 0 establishes one authoritative flow:

1. `POST /api/v1/playback/start` consumes protocol-v3 context and returns one
   executable plan or `adaptation_unavailable`.
2. `POST /api/v1/playback/{session_id}/replan` consumes the failed `plan_id`,
   `replan_request_id`, `plan_attempt_id`, `output_route_generation`, position,
   selected tracks, classified failure, current capabilities, and
   `plan_attempt_key`/`attempted_plan_keys`, then idempotently returns a
   replacement plan or terminal result.
3. `POST /api/v1/playback/route-events` ingests the versioned event schema from
   architecture section 9. It uses `playback_attempt_id` plus an optional
   session ID so protocol and terminal pre-session failures are reportable.
4. Server-internal transcode/remux startup returns its final stream URL and
   effective recipe in the plan. Android no longer invents H.264/AAC/bitrate or
   segment settings.

Release A removes the Android `/api/v1/playback/decide` and session-scoped
route-event callers. Contract tests assert that only the three canonical paths
above remain.

The v3 `/start` response advertises `protocol_version=3` and server feature
`playback_plan_v3`. Absence produces the local UI/failure code
`server_upgrade_required`; it is not an `adaptation_unavailable` reason supplied
by an older server. Release A does not use the incomplete legacy routing path.
If an incompatible response allocated a legacy session, Android stops it before
showing the local error.

The minimum server version must be published and allowed a named upgrade dwell
before Release A rollout. Legacy-enum tolerance remains useful for cached plans,
rolling/mixed v3 server nodes, and defensive decoding; it does not make pre-v3
servers supported.

### 2.2 Server traceability

| Architecture requirement | Phase 0 server artifact | Acceptance proof |
| --- | --- | --- |
| Section 3: decision, playable plan, and terminal response | Versioned start/replan request and response schemas plus direct-play revalidation | Cross-repository golden fixtures and invalid-direct tests |
| Section 4: output-route-aware capabilities | Consumed protocol-v3 client/output schema and resolver inputs | Per-codec, DV, audio-layout, and subtitle fixtures |
| Section 5: HDR/DV | Named transformation registry with required tool/hardware checks | Output metadata/color validation for every advertised transformation |
| Section 6: audio | Video-copy/audio-adaptation recipes and effective audio result | Channel/layout and transcode-disabled tests |
| Section 7: subtitles | Stable track identity, mode, and converted-artifact fields | Embedded/sidecar render, convert, burn-in, and unavailable fixtures |
| Section 9: telemetry | Attempt-scoped ingestion, storage, privacy policy, and aggregate release report | Event fixtures plus the exact release queries |

### 2.3 Phase 0 proof

Shared JSON fixtures and cross-repository tests cover:

- current and minimum supported clients;
- missing, current, and unknown protocol versions/features;
- stale `mpv_direct` values without losing the rest of a response;
- direct revalidation against codec/profile/range/output blockers;
- track and quality selection;
- video-copy/audio-adaptation and subtitle render/convert/burn-in;
- every advertised HDR/DV transformation;
- transcode disabled, capacity unavailable, policy denied, and missing tooling;
- `replan_request_id` idempotency, session replacement, seek restoration,
  output-route changes, `plan_attempt_key`/`attempted_plan_keys`, and loop
  prevention;
- canonical `plan_attempt_key` derivation fixtures covering recipe ordering,
  output-route changes, and local audio mutations;
- both subtitle-fidelity preference/policy values and their server/admin
  resolution;
- route-event ingestion and the exact queries used for release metrics.
- absence of `/playback/decide` and session-scoped route-event calls after the
  canonical endpoint migration.

Phase 0 exits only when the published minimum server is deployed on validation
servers, contract tests pass against that build, telemetry is queryable, and
every enabled transformation/recipe registry entry has a fixture that fetches
and prepares its returned stream or verifies its terminal result.

## 3. Release A — Disable MPV behaviour

MPV remains packaged only to preserve a clean Release A rollback. It is never
constructed, selected, advertised, or used for recovery.

| Workstream | Required Android change |
| --- | --- |
| Backend/service | Selector and factory return Media3 only. Stop sending and handling `SET_ENGINE`; no path calls `createMpvPlayer`. Keep one service-owned Media3 engine. |
| Capability/direct policy | Remove MPV codec/container unions, omit the MPV capability envelope, emit protocol-v3 features, correct per-codec/DV/audio/subtitle reporting, and stop forcing direct play. |
| Plan execution | Consume complete server plans and use `POST /api/v1/playback/{session_id}/replan` after classified failure. Delete client-invented transcode recipes; derive and track `plan_attempt_id`/`plan_attempt_key`/`attempted_plan_keys`; preserve state for the one allowed transport reopen. |
| Watchdogs | Reuse and centralize the existing phone/TV watchdog. Separate no-input startup stalls from queued-input/zero-output decoder stalls. |
| Audio | Change extensions to fallback/`ON` ordering, implement an encoding-plus-layout passthrough-suppression/reprepare hook, bound local PCM recovery to one attempt, and expose PCM-only audio-delay behaviour. |
| Subtitles/UI | Remove MPV-only style/scale controls and casts. Use stable track identity plus the server's `render`, `convert`, or `burn_in` decision. |
| Diagnostics | Emit `POST /api/v1/playback/route-events` and show a debug summary of plan, delivery, decoder, effective range, audio mode, subtitle mode, and fallback reason. |

Specific correctness work includes:

- fix the Dolby Vision probe so profiles 4/6 are not reported as Profile 7 and
  Profile 7 checks the required DV and HEVC decoder combination;
- remove unconditional Profile 8 direct play and use server-supplied variant and
  base-layer compatibility metadata;
- build the private FFmpeg decoder extension from the same Media3 1.11.0 tag as
  the app runtime and retain its pinned NDK/FFmpeg inputs;
- test AVI as Media3 extractor-supported rather than classifying it as MPV-only;
- test embedded and sidecar PGS/VobSub/DVB separately instead of treating every
  bitmap subtitle as burn-in-only.

### 3.1 Legacy wire values

During one named compatibility window Android tolerantly decodes
`PlaybackEngineKind.MPV_DIRECT` (wire token `"mpv_direct"`),
`CLIENT_LOCAL_LOOPBACK` (`"client_local_loopback"`), and `EXTERNAL_PLAYER`
(`"external_player"`), but never advertises or selects them. MPV is omitted
from new capability maps; there is no ambiguous disabled tombstone.

If a stale value arrives, Android requests a replan. If no valid Media3 plan is
available, it presents the server's terminal reason. Legacy enum values are
removed only after all supported servers have stopped emitting them for the
recorded observation window.

### 3.2 Release A automated gates

- `./gradlew :androidApp:assembleDebug` passes.
- `./gradlew :androidTvApp:assembleDebug` passes.
- `./gradlew test` passes.
- Selector, factory, service, and UI tests prove no MPV construction or engine
  command.
- Capability payloads contain no MPV codec/container union or MPV envelope.
- Client/server golden fixtures from Phase 0 pass in Android tests.
- An explicit client hint cannot bypass server direct-play validation.
- A stale MPV enum preserves the rest of the response and produces one replan.
- Decoder-stall tests require queued input and zero rendered/skipped/dropped
  output; slow startup remains a separate failure class.
- Encoded-audio failure produces at most one local PCM retry before replan.
- Network/startup recovery permits exactly one same-plan reopen, preserves
  position/tracks/session state, and records `transport_reopen_used`.
- Subtitle tests cover stable selection, all four plan modes, and both
  fidelity-preference/policy values.
- Endpoint tests prove `/playback/decide` and session-scoped route-events are
  absent from Android production callers.
- `git diff --check` passes.

## 4. Pre-release validation and soak

Every release records its proof in
`docs/playback/evidence/<release-id>.json`. The checked-in record names the
owner, Android/server revisions, build artifacts, validation servers/devices,
fixture-manifest revision, metric queries, denominators, sample sizes,
thresholds, observation window, evidence links, and final decision. It contains
no credentials, media paths, user identifiers, or raw playback events.

### 4.1 Pre-A lab and hardware gates

These gates pass before any Release A rollout. Run phone and TV fixtures on at
least:

- a phone or tablet;
- a current Google/Android TV device;
- a Shield-class Android TV connected through an AVR/eARC route;
- an API 24 or 25 device;
- a 32-bit device if the release still claims that ABI.

| Fixture | Required observation |
| --- | --- |
| H.264/HEVC SDR in MP4, MKV, and AVI | Direct play only for extractor/codec/seek combinations that pass; otherwise declared adaptation. |
| MOV/QT and TS/M2TS | Test direct, seeking, tracks, subtitles, and recovery; do not force remux solely from the extension. |
| HDR10, HDR10+, and HLG | A claimed HDR output requires correct planned delivery plus display/TV evidence, including after server adaptation. Without external evidence, record only planned/effective range. |
| DV P5, P7 MEL, P7 FEL, and every P8 variant advertised by the server | The fixture manifest enumerates each claimed variant; initially include P8.1, P8.2, and P8.4 if advertised. Test decoder, plan, display mode, and visible output separately. Unsupported cases use a validated server transformation or `adaptation_unavailable`. |
| E-AC-3/JOC, TrueHD/Atmos, DTS-HD, AC-3, and AAC | Pass when an advertised encoding/layout produces AVR-confirmed bitstream; otherwise the plan must declare PCM or server adaptation with no unclassified failure. |
| Embedded and sidecar ASS, PGS, VobSub, and DVB | Verify selected track, render fidelity, conversion/burn-in declaration, and HDR effect. |
| `auto`, `original`, `2160p`, `1080p`, `720p`, `480p` | Returned effective recipe matches policy, never upscales, and does not replay original for a requested lower rung. |
| Transcoding disabled/capacity exhausted/tool missing | Terminal reason is actionable; unsupported original bytes are not retried. |

App diagnostics record planned/effective format. Only external TV/display/AVR
evidence may be labelled actual output mode or codec.

### 4.2 Staged-rollout telemetry and soak

Release metrics use a named set of controlled validation servers plus consenting
prerelease testers. Each server produces aggregate counts; the release owner
combines only those aggregate reports in the evidence file. No central raw-event
collection is implied.

Before rollout, the release owner records for every metric:

- owner and exact query;
- observation period;
- denominator and minimum sample size;
- warning and rollback thresholds;
- rollback decision authority.

No threshold may remain “TBD.” The previous API-24/25 and 32-bit cohort does not
provide a historical network baseline because playback telemetry did not exist.
Release A establishes the first comparable baseline through staged rollout.

Required metrics include playback-start success, unclassified failure,
decoder-no-output, replan success, terminal-unavailable reasons, local audio
retry success, direct/remux/transcode distribution, and rendered-first-frame
time.

Release B is blocked until Release A completes the observation period within
the recorded numeric budgets and all required fixture evidence is attached to
the checked-in release record.

## 5. Release B — Remove MPV

Release B removes dormant MPV packaging and all remaining implementation
surfaces:

- `dev.jdtech.mpv` version/dependency aliases and `implementation(libs.libmpv)`;
- both manifest `tools:overrideLibrary="dev.jdtech.mpv"` entries and comments;
- MPV JNI/R8/ProGuard rules and packaging assumptions;
- `common/player/mpv/`, MPV backend/factory/device-floor/software-codec source,
  imports, tests, and authentication helpers;
- engine commands, mutex/state transfer, pending/previous-player lifecycle, and
  delayed old-player release;
- MPV preferences, subtitle/video controls, phone/TV UI, and policy tests;
- stale MPV references in root `README.md`, `FEATURES.md`, and Gradle comments;
  update those files rather than deleting them.

Keep the service-owned Media3 player, MediaSession, PiP, position flow, Media3
subtitle offset, PCM audio-delay processor, analytics, and the separately
version-aligned FFmpeg audio extension.

### 5.1 Artifact verification

```text
./gradlew test
./gradlew :androidApp:assembleDebug
./gradlew :androidTvApp:assembleDebug
```

Release B is incomplete unless:

- dependency inspection contains no `dev.jdtech.mpv`;
- merged manifests contain no MPV override;
- APK/AAB inspection contains no libmpv JNI objects;
- production source contains no MPV construction, backend, imports, engine
  command, preference, or controller;
- Release B adds and runs `scripts/check-no-mpv-runtime.sh`; its checked-in
  allowlist names only exact temporary serialized-model/test paths. It excludes
  these specifications and release history, never a broad source directory;
- manifest/build policy tests assert MPV absence.

The evidence record captures the test output, dependency report, merged
manifests, APK/AAB JNI listing, and scoped-search result. A broad
`rg -i 'mpv'` is not a gate because these specifications intentionally retain
historical and wire-compatibility terminology.

## 6. Rollback and completion

| Failure point | Required action |
| --- | --- |
| Phase 0 contract, conversion, or telemetry fails | Do not ship Release A. |
| Release A functional or threshold failure | Halt staged rollout or publish the reverted Release A build; packaged MPV makes that revert complete. |
| Release B packaging, build, or API-floor failure | Revert Release B; Media3-only behaviour remains active. |
| A Media3 functional failure is discovered after Release B | Under the recorded rollback authority/review, temporarily restore the last known-good pre-A app, or revert Release B and then Release A. Reverting B alone does not change playback behaviour. |
| Product requires functional MPV again | Open a separate reviewed decision that restores both behaviour and packaging. |

Migration is complete only when MPV is absent from runtime and artifacts, all
supported servers honor Media3-only planning, the legacy-enum observation
window has closed, and every release gate has recorded evidence.
