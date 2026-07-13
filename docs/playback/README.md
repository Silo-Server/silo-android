# Silo Android Playback Architecture

Status: **Android implementation and dev-server v3 validation complete; 4K DV
and passthrough hardware validation remain gated**.

This directory is the source of truth for the next Silo Android video player.
This directory supersedes the removed legacy Media3/dual-engine notes.

## Product decision

Silo Android will ship one in-process video engine: **Media3 ExoPlayer**. MPV
will be removed from app artifacts, capability reporting, engine selection,
recovery, and UI.

This is a new routing and compatibility architecture, not a replacement for
every Android player component. The retained foundation and target runtime are
defined in [architecture section 2](01-media3-only-player-architecture.md#2-target-runtime).

Media3 is the AndroidX media framework; ExoPlayer is its default `Player`
implementation. Silo already uses that implementation. [Checkout dependency](../../android-shared/build.gradle.kts) ·
[player factory](../../android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlayerFactory.kt)
See the
[Media3 ExoPlayer overview](https://developer.android.com/media/media3/exoplayer)
and [migration guide](https://developer.android.com/media/media3/exoplayer/migration-guide).

## Document ownership

| Document | Canonical content |
| --- | --- |
| [Architecture](01-media3-only-player-architecture.md) | Runtime invariants, server/client contract, capability schema, HDR/DV, audio, subtitles, recovery, and telemetry. |
| [Migration and validation](02-migration-compatibility-validation.md) | Phase ordering, compatibility window, removal inventory, release gates, hardware fixtures, and rollback. |
| [Reference review](03-reference-implementation-review.md) | Source-pinned Wholphin/Plezy observations. It is evidence, not another implementation plan. |
| [Implementation status](04-implementation-status-and-dv-handoff.md) | Code, automated proof, dev-server v3 status, and the 4K Dolby Vision handoff checklist. |
| [Shield 1080p capability audit](05-shield-1080p-playback-capability-audit.md) | Live protocol-v3 route matrix, catalog coverage, current direct-play gaps, and prioritized causes. |
| [Device-correction evidence and design](06-device-quirk-evidence-and-design.md) | Current Jellyfin Android TV, Jellyfin Android, Wholphin, Plezy, Android platform, and issue evidence for the server/client quirk layer. |

Requirements are defined once in their owning document. Other documents link to
that section instead of restating it; this prevents future agents from treating
two similar passages as separate requirements.

The implementation-status document records proof and remaining gates; it does
not create another set of requirements.

## Release gate

Android work may proceed in parallel, but Release A must not ship until
[migration Phase 0](02-migration-compatibility-validation.md#2-phase-0-server-readiness)
is marked complete against a named minimum server revision.

## Evidence boundary

- **Current-code statements** must cite this checkout or the matching server
  revision.
- **Framework statements** must cite pinned primary sources where versions
  matter.
- **Device outcomes** remain unproven until tested on the named device, display,
  HDMI/eARC chain, and AVR. A decoded frame alone does not prove correct HDR or
  passthrough output.
