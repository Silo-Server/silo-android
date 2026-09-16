package org.siloserver.silo.model.calendar

import org.siloserver.silo.model.catalog.isEpisodeItemType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server filter presets for GET /api/v2/calendar. Web exposes Following / Trending / All. */
object CalendarFilter {
    const val All = "all"
    const val Everything = "everything"
    const val Following = "following"
    const val Favorites = "favorites"
    const val Watchlist = "watchlist"
    const val Popular = "popular"
    const val Trending = "trending"
}

object CalendarItemType {
    const val Movie = "movie"
    const val Episode = "episode"
}

object CalendarBadge {
    const val SeriesPremiere = "series_premiere"
    const val SeasonPremiere = "season_premiere"
    const val Finale = "finale"
}

@Serializable
data class CalendarResponse(
    val events: List<CalendarDay> = emptyList(),
)

@Serializable
data class CalendarDay(
    val date: String,
    val items: List<CalendarItem> = emptyList(),
)

@Serializable
data class CalendarItem(
    @SerialName("content_id") val contentId: String,
    val type: String,
    val title: String,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("air_date") val airDate: String,
    @SerialName("air_time") val airTime: String? = null,
    @SerialName("air_at") val airAt: String? = null,
    @SerialName("air_timezone") val airTimezone: String? = null,
    @SerialName("local_air_date") val localAirDate: String,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    val watched: Boolean = false,
    val badges: List<String> = emptyList(),
) {
    val isEpisode: Boolean get() = isEpisodeItemType(type)

    /** Detail-route target: the series page for episodes, the item itself otherwise. */
    val detailContentId: String get() = if (isEpisode) seriesId ?: contentId else contentId
}
