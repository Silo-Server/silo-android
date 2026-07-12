package org.siloserver.silo.common.player.video

val media3OriginalPlaybackContainers: List<String> =
    listOf(
        "mp4", "m4v", "mov", "qt",
        "webm", "mkv", "matroska", "avi",
        "ts", "mpegts", "mpeg-ts", "m2ts", "mts",
    )

fun normalizedPlaybackContainer(container: String?): String? =
    container
        ?.trim()
        ?.trimStart('.')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
