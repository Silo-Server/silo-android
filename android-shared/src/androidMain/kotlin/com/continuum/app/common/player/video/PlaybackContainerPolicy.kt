package com.continuum.app.common.player.video

val media3OriginalPlaybackContainers: List<String> =
    listOf("mp4", "m4v", "webm", "mkv")

val mpvOriginalPlaybackContainers: List<String> =
    listOf(
        "mkv",
        "matroska",
        "mp4",
        "m4v",
        "webm",
        "avi",
        "mov",
        "qt",
        "ts",
        "mpegts",
        "mpeg-ts",
        "m2ts",
        "mts",
    )

val directOriginalPlaybackContainers: List<String> =
    (media3OriginalPlaybackContainers + mpvOriginalPlaybackContainers).distinct()

/**
 * Containers MPV can mount directly when the selected stream is otherwise
 * codec-compatible.
 */
fun isMpvOriginalPlaybackContainer(container: String?): Boolean =
    normalizedPlaybackContainer(container) in mpvOriginalPlaybackContainers

/**
 * Original containers that should prefer MPV at playback time because Media3 is
 * more likely to stumble on container semantics, timestamping, or attachments.
 * Matroska stays MPV-capable, but Media3 is the faster default on modern TV
 * devices and gives us staged buffering + auth refresh instead of a blind MPV
 * startup stall.
 */
fun isMpvPreferredOriginalPlaybackContainer(container: String?): Boolean =
    when (normalizedPlaybackContainer(container)) {
        "avi",
        "mov", "qt",
        "ts", "mpegts", "mpeg-ts", "m2ts", "mts" -> true
        else -> false
    }

fun normalizedPlaybackContainer(container: String?): String? =
    container
        ?.trim()
        ?.trimStart('.')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
