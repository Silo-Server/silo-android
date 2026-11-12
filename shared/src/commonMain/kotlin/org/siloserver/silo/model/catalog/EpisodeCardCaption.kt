package org.siloserver.silo.model.catalog

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText

/**
 * Text for the caption under an episode card, the same on the Apple and web
 * clients: "S01E02 · Pilot", then the air date and runtime. Each app formats the
 * air date with its own calendar-date formatter and passes the result in.
 */
object EpisodeCardCaption {
    /**
     * "S01E02", zero-padded so codes line up across a rail. A code is
     * technical, so its numbers stay ASCII in every language (D78).
     */
    private fun code(seasonNumber: Int, episodeNumber: Int): UiText = UiText.Key(
        SharedString.EpisodeTag,
        listOf(UiText.Raw(codeNumber(seasonNumber)), UiText.Raw(codeNumber(episodeNumber))),
    )

    private fun codeNumber(value: Int): String = value.toString().padStart(2, '0')

    /** "S01E02 · Pilot", or the code alone when the episode has no title. */
    fun title(episode: EpisodeListItem): UiText {
        val code = code(episode.seasonNumber, episode.episodeNumber)
        val title = episode.title?.trim().orEmpty()
        return if (title.isEmpty()) code else UiText.Key(SharedString.EpisodeCardTitle, listOf(code, title))
    }

    /** "Feb 18, 2026  ·  52m", or null when neither part is known. */
    fun metadata(airDate: String?, runtimeMinutes: Int): UiText? {
        val date = airDate?.takeIf { it.isNotBlank() }
        val runtime = runtime(runtimeMinutes)
        return when {
            date != null && runtime != null -> UiText.Key(SharedString.EpisodeCardMetadata, listOf(date, runtime))
            date != null -> UiText.Raw(date)
            else -> runtime
        }
    }

    /** "52m" or "1h 5m"; null when the runtime is unknown. */
    private fun runtime(minutes: Int): UiText? = when {
        minutes <= 0 -> null
        minutes >= 60 -> UiText.Key(
            SharedString.RuntimeHoursMinutes,
            listOf(UiText.Number(minutes / 60), UiText.Number(minutes % 60)),
        )
        else -> UiText.Key(SharedString.RuntimeMinutesShort, listOf(UiText.Number(minutes)))
    }
}
