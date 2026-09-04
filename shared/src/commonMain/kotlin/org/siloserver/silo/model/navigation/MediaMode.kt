package org.siloserver.silo.model.navigation

import org.siloserver.silo.model.personal.UserLibrary

enum class MediaMode(val label: String) {
    Video("Video"),
    Audio("Audio"),
    Reading("Reading"),
}

enum class ReadingFormatFilter(val label: String) {
    All("All"),
    Ebooks("Ebooks"),
    Audiobooks("Audiobooks"),
}

data class MediaModeCapabilities(
    val modes: List<MediaMode> = emptyList(),
) {
    val hasVideo: Boolean get() = MediaMode.Video in modes
    val hasAudio: Boolean get() = MediaMode.Audio in modes
    val hasReading: Boolean get() = MediaMode.Reading in modes

    fun mobileModes(): List<MediaMode> = modes
    fun tvModes(): List<MediaMode> = modes.filterNot { it == MediaMode.Reading }
}

private val videoLibraryTypes = setOf(
    "movie",
    "movies",
    "series",
    "show",
    "shows",
    "tv",
    "video",
    // A server library holding movies AND series in one folder. Omitting it
    // here did not degrade the library — it erased it: the type mapped to no
    // MediaMode, so the library never reached navigation, search or browse on
    // either platform, with nothing to indicate anything was missing.
    // silo-apple hit the same thing and fixed it in #93.
    "mixed",
)

private val audioLibraryTypes = setOf(
    "music",
    "album",
    "albums",
    "artist",
    "artists",
    "audio",
)

private val ebookLikeLibraryTypes = setOf(
    "ebook",
    "ebooks",
    "book",
    "books",
    "comic",
    "comics",
    "manga",
    "reading",
)

private val audiobookLikeLibraryTypes = setOf(
    "audiobook",
    "audiobooks",
)

private fun normalizedLibraryType(type: String?): String? =
    type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

fun mobileMediaModeForLibraryType(type: String?): MediaMode? = when {
    normalizedLibraryType(type) in videoLibraryTypes -> MediaMode.Video
    normalizedLibraryType(type) in audioLibraryTypes || isAudiobookLikeLibraryType(type) -> MediaMode.Audio
    isEbookLikeLibraryType(type) -> MediaMode.Reading
    else -> null
}

fun tvMediaModeForLibraryType(type: String?): MediaMode? = when {
    normalizedLibraryType(type) in videoLibraryTypes -> MediaMode.Video
    normalizedLibraryType(type) in audioLibraryTypes || isAudiobookLikeLibraryType(type) -> MediaMode.Audio
    else -> null
}

fun isEbookLikeLibraryType(type: String?): Boolean =
    normalizedLibraryType(type) in ebookLikeLibraryTypes

fun isAudiobookLikeLibraryType(type: String?): Boolean =
    normalizedLibraryType(type) in audiobookLikeLibraryTypes

fun isReadingLibraryType(type: String?): Boolean =
    isEbookLikeLibraryType(type) || isAudiobookLikeLibraryType(type)

fun UserLibrary.matchesReadingFormat(format: ReadingFormatFilter): Boolean =
    when (format) {
        ReadingFormatFilter.All -> isReadingLibraryType(type)
        ReadingFormatFilter.Ebooks -> isEbookLikeLibraryType(type)
        ReadingFormatFilter.Audiobooks -> isAudiobookLikeLibraryType(type)
    }

fun Iterable<UserLibrary>.filterByReadingFormat(format: ReadingFormatFilter): List<UserLibrary> =
    filter { it.matchesReadingFormat(format) }

fun Iterable<UserLibrary>.availableReadingFormatFilters(): List<ReadingFormatFilter> {
    val libraries = toList()
    val hasEbooks = libraries.any { isEbookLikeLibraryType(it.type) }
    val hasAudiobooks = libraries.any { isAudiobookLikeLibraryType(it.type) }
    return buildList {
        if (hasEbooks && hasAudiobooks) add(ReadingFormatFilter.All)
        if (hasEbooks) add(ReadingFormatFilter.Ebooks)
        if (hasAudiobooks) add(ReadingFormatFilter.Audiobooks)
    }
}

fun Iterable<UserLibrary>.readingLibraries(): List<UserLibrary> =
    filterByReadingFormat(ReadingFormatFilter.All)

fun Iterable<UserLibrary>.mobileMediaModeCapabilities(): MediaModeCapabilities =
    mediaModeCapabilitiesUsing(::mobileMediaModeForLibraryType)

fun Iterable<UserLibrary>.tvMediaModeCapabilities(): MediaModeCapabilities =
    mediaModeCapabilitiesUsing(::tvMediaModeForLibraryType)

private fun Iterable<UserLibrary>.mediaModeCapabilitiesUsing(
    mapper: (String?) -> MediaMode?,
): MediaModeCapabilities {
    val present = mapNotNull { mapper(it.type) }.toSet()
    return MediaModeCapabilities(
        modes = listOf(MediaMode.Video, MediaMode.Audio, MediaMode.Reading).filter { it in present },
    )
}
