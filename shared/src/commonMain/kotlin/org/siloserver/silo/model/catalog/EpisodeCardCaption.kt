package org.siloserver.silo.model.catalog

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText

/**
 * Text for the caption under an episode card, the same on the Apple and web
 * clients: "S01E02 · Pilot", then the air date and runtime. Codes come from
 * [EpisodeLabel]. Each app formats the
 * air date with its own calendar-date formatter and passes the result in.
 */
object EpisodeCardCaption {
    /**
     * "S01E02 · Pilot", or the code alone when the episode has no title. For a
     * date-labeled series ([episodeLabels] `air_date`, sports) the title leads
     * on its own, since the air date is on the metadata line below; an
     * untitled episode shows its air date, or its code when undated.
     */
    fun title(episode: EpisodeListItem, episodeLabels: String? = null): UiText {
        val title = episode.title?.trim().orEmpty()
        if (EpisodeLabel.labelsByAirDate(episodeLabels)) {
            return if (title.isEmpty()) {
                EpisodeLabel.text(episode.seasonNumber, episode.episodeNumber, episodeLabels, episode.airDate)
            } else {
                UiText.Raw(title)
            }
        }
        val code = EpisodeLabel.code(episode.seasonNumber, episode.episodeNumber)
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
