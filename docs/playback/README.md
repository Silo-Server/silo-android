# Silo Android Playback Architecture

Status: **the Android client is ported to the platform-neutral playback-v3
wire contract; live validation still requires a server built from the matching
neutral-v3 revision**.

This directory owns the Android Media3 runtime and its validation history. The
normative wire contract is the server repository's
`docs/architecture/playback-protocol-v3.md`; when these older migration notes
disagree with it, the server contract wins. In particular, Android no longer
advertises engine names or computes plan-attempt keys.

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
| [Architecture](01-media3-only-player-architecture.md) | Android runtime invariants plus the pre-neutral contract history. Neutral wire semantics come from the server contract. |
| [Migration and validation](02-migration-compatibility-validation.md) | Historical Media3-only migration plan, hardware fixtures, and rollback evidence. |
| [Reference review](03-reference-implementation-review.md) | Source-pinned Wholphin/Plezy observations. It is evidence, not another implementation plan. |
| [Implementation status](04-implementation-status-and-dv-handoff.md) | Code, automated proof, dev-server v3 status, and the 4K Dolby Vision handoff checklist. |
| [Intro skip](intro-skip.md) | Where the never/ask/always prompt lives, and the rules the server spec pins. |
| [Shield 1080p capability audit](05-shield-1080p-playback-capability-audit.md) | Live protocol-v3 route matrix, catalog coverage, current direct-play gaps, and prioritized causes. |
| [Device-correction evidence and design](06-device-quirk-evidence-and-design.md) | Current Jellyfin Android TV, Jellyfin Android, Wholphin, Plezy, Android platform, and issue evidence for the server/client quirk layer. |

Requirements are defined once in their owning document. Other documents link to
that section instead of restating it; this prevents future agents from treating
two similar passages as separate requirements.

The implementation-status document records proof and remaining gates; it does
not create another set of requirements.

## Release gate

The neutral Android build must not ship until a named server revision exposing
the matching platform-neutral v3 contract is published and deployed. A
pre-neutral `playback_plan_v3` server is not compatible merely because the
feature token has the same name.

## Evidence boundary

- **Current-code statements** must cite this checkout or the matching server
  revision.
- **Framework statements** must cite pinned primary sources where versions
  matter.
- **Device outcomes** remain unproven until tested on the named device, display,
  HDMI/eARC chain, and AVR. A decoded frame alone does not prove correct HDR or
  passthrough output.
