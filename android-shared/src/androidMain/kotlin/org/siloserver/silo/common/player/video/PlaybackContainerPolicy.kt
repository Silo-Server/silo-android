package org.siloserver.silo.common.player.video

/**
 * Containers Media3 opens directly for video sources.
 */
val media3OriginalVideoContainers: List<String> =
    listOf(
        "mp4", "m4v", "mov", "qt",
        "webm", "mkv", "matroska", "avi",
        "ts", "mpegts", "mpeg-ts", "m2ts", "mts",
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
