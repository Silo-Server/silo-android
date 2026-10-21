package org.siloserver.silo.model.catalog

/**
 * Text for the caption under an episode card, the same on the Apple and web
 * clients: "S01E02 · Pilot", then the air date and runtime. Each app formats the
 * air date with its own calendar-date formatter and passes the result in.
 */
object EpisodeCardCaption {
    /** "S01E02", zero-padded so codes line up across a rail. */
    private fun code(seasonNumber: Int, episodeNumber: Int): String =
        "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"

    /** "S01E02 · Pilot", or the code alone when the episode has no title. */
    fun title(episode: EpisodeListItem): String {
        val code = code(episode.seasonNumber, episode.episodeNumber)
        val title = episode.title?.trim().orEmpty()
        return if (title.isEmpty()) code else "$code · $title"
    }

    /** "Feb 18, 2026  ·  52m", or null when neither part is known. */
    fun metadata(airDate: String?, runtimeMinutes: Int): String? =
        listOfNotNull(airDate?.takeIf { it.isNotBlank() }, runtime(runtimeMinutes))
            .takeIf { it.isNotEmpty() }
            ?.joinToString("  ·  ")

    /** "52m" or "1h 5m"; null when the runtime is unknown. */
    private fun runtime(minutes: Int): String? = when {
        minutes <= 0 -> null
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
