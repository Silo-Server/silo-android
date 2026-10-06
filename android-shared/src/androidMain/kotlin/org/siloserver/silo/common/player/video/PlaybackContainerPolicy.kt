package org.siloserver.silo.common.player.video

/**
 * Containers Media3 opens directly for video sources.
 *
 * `m2ts` and `mts` are deliberately absent. Blu-ray and AVCHD files carry
 * 192-byte BDAV packets, and Media3's `TsExtractor` reads only 188-byte
 * transport-stream packets, so no extractor recognizes them and direct play
 * fails with `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`. Leaving them out
 * makes the server remux these files for playback and for single-title and
 * season downloads. A whole-series download at Original quality still fetches
 * the original bytes, because the server does not check device capabilities
 * for that batch. Add them back only once Media3 supports 192-byte packets
 * and a device test passes.
 */
val media3OriginalVideoContainers: List<String> =
    listOf(
        "mp4", "m4v", "mov", "qt",
        "webm", "mkv", "matroska", "avi",
        "ts", "mpegts", "mpeg-ts",
    )

/**
 * Bare audio containers Media3 opens directly, via its own extractors — Mp3,
 * Flac, Wav, and Ogg are first-party.
 *
 * These are listed apart from the video containers because they only ever
 * arrive as an audio-only source (audiobooks and music), never as a video
 * file's container. Omitting them is not cosmetic under protocol v3: the
 * audio-only planner gates its `original_http` route on the source container
 * appearing in the advertised list, and the `progressive` delivery this client
 * would otherwise fall back to is disabled pending a seekable transport. An
 * `.mp3` audiobook with neither would plan to `adaptation_unavailable` — no
 * playable route at all — despite Media3 being perfectly able to play it.
 */
val media3OriginalAudioContainers: List<String> =
    listOf(
        "mp3", "m4a", "m4b", "aac", "flac",
        "wav", "ogg", "oga", "opus",
    )

/**
 * The full direct-play container advertisement: what the client claims it can
 * open without server adaptation, for any source.
 */
val media3OriginalPlaybackContainers: List<String> =
    media3OriginalVideoContainers + media3OriginalAudioContainers

fun normalizedPlaybackContainer(container: String?): String? =
    container
        ?.trim()
        ?.trimStart('.')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
