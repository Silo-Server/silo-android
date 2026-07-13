# Reference Implementation Review: General Player Stack

This evidence snapshot was reviewed on 2026-07-11 at
[Wholphin `26124a5`](https://github.com/damontecres/Wholphin/tree/26124a534828bbcac6f4875ee8aeabed7ad3eef6)
and [Plezy `a23c7f2`](https://github.com/edde746/plezy/tree/a23c7f215cf8f98154c8b23807eb002b4a115a62).
Neither app is authoritative for Silo; this document records only the patterns
that informed the [architecture](01-media3-only-player-architecture.md).

## Source findings

| Area | Wholphin | Plezy | What the source establishes |
| --- | --- | --- | --- |
| Engine selection | “Prefer MPV” routes HDR, and 4K when the user has disabled MPV hardware decoding, to ExoPlayer. This is a preference check, not measured software decoding. [Source](https://github.com/damontecres/Wholphin/blob/26124a534828bbcac6f4875ee8aeabed7ad3eef6/app/src/main/java/com/github/damontecres/wholphin/ui/playback/PlaybackViewModel.kt#L226-L250) | ExoPlayer is the default, but ordinary ExoPlayer errors can hand off to MPV. [Default](https://github.com/edde746/plezy/blob/a23c7f215cf8f98154c8b23807eb002b4a115a62/lib/services/settings_service.dart#L429-L435) · [Error path](https://github.com/edde746/plezy/blob/a23c7f215cf8f98154c8b23807eb002b4a115a62/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt#L1216-L1224) | Both remain dual-engine examples; neither establishes a one-engine requirement. |
| Server fallback | Sends backend-specific device capabilities and can re-request playback with direct play/stream disabled after a direct failure. [Request](https://github.com/damontecres/Wholphin/blob/26124a534828bbcac6f4875ee8aeabed7ad3eef6/app/src/main/java/com/github/damontecres/wholphin/ui/playback/PlaybackViewModel.kt#L650-L680) · [Fallback](https://github.com/damontecres/Wholphin/blob/26124a534828bbcac6f4875ee8aeabed7ad3eef6/app/src/main/java/com/github/damontecres/wholphin/ui/playback/PlaybackViewModel.kt#L1389-L1405) | Ordinary playback errors use the local MPV fallback above; this is not an error-to-server replan. | Only Wholphin demonstrates error-to-server re-requesting. |
| Renderer and passthrough policy | Supports `ON`, `PREFER`, and `OFF`; fallback/`ON` is the default. [Factory](https://github.com/damontecres/Wholphin/blob/26124a534828bbcac6f4875ee8aeabed7ad3eef6/app/src/main/java/com/github/damontecres/wholphin/services/PlayerFactory.kt#L97-L109) · [Default](https://github.com/damontecres/Wholphin/blob/26124a534828bbcac6f4875ee8aeabed7ad3eef6/app/src/main/java/com/github/damontecres/wholphin/preferences/AppPreference.kt#L670-L674) | Uses `ON`; its TrueHD choice also consults Android audio capabilities. [Renderer](https://github.com/edde746/plezy/blob/a23c7f215cf8f98154c8b23807eb002b4a115a62/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt#L564-L586) · [TrueHD path](https://github.com/edde746/plezy/blob/a23c7f215cf8f98154c8b23807eb002b4a115a62/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt#L2046-L2099) | Both show renderer-ordering choices; Plezy additionally demonstrates a capability-aware TrueHD decision. |
| Audio recovery | — | On an `AudioTrack` failure, a custom sink blocks the failed encoded output and ExoPlayer is re-prepared with a bounded retry count. [Retry](https://github.com/edde746/plezy/blob/a23c7f215cf8f98154c8b23807eb002b4a115a62/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/ExoPlayerCore.kt#L1238-L1309) · [Sink hook](https://github.com/edde746/plezy/blob/a23c7f215cf8f98154c8b23807eb002b4a115a62/android/app/src/main/kotlin/com/edde746/plezy/exoplayer/PlezyRenderersFactory.kt#L193-L213) | Plezy demonstrates one concrete same-ExoPlayer recovery shape. |
| Subtitles and containers | Integrates `ass-media` into Media3 and advertises a broad static container profile. [ASS](https://github.com/damontecres/Wholphin/blob/26124a534828bbcac6f4875ee8aeabed7ad3eef6/app/src/main/java/com/github/damontecres/wholphin/services/PlayerFactory.kt#L111-L128) · [Containers](https://github.com/damontecres/Wholphin/blob/26124a534828bbcac6f4875ee8aeabed7ad3eef6/app/src/main/java/com/github/damontecres/wholphin/util/profile/DeviceProfileUtils.kt#L193-L227) | Uses a custom libass overlay. | Neither implementation proves Silo container or subtitle compatibility. |

Device-specific capability corrections, firmware-sensitive rules, and runtime
watchdogs are owned by the current, four-project
[device-correction evidence review](06-device-quirk-evidence-and-design.md).
They are intentionally not duplicated in this older general-stack snapshot.

## Evidence limits

The peer code shows implementation shapes, not device guarantees. It does not
prove Silo's Dolby Vision output, HDR signaling, AVR passthrough, subtitle
fidelity, or server compatibility. Those decisions and gates are defined only
in the architecture and migration documents.
