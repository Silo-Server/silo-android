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

    /** A movie listed on its digital release (`/api/v2` only). */
    const val Digital = "digital"

    /** A movie listed on its physical (disc) release (`/api/v2` only). */
    const val Physical = "physical"
}

/**
 * Poster pill label for a calendar badge, or null to hide a badge this client
 * does not know. Same labels as web, iOS, and tvOS. PREMIERE, not SERIES
 * PREMIERE, fits a poster badge, and NEW SEASON already marks season premieres.
 */
fun calendarBadgeLabel(badge: String): String? = when (badge) {
    CalendarBadge.SeriesPremiere -> "PREMIERE"
    CalendarBadge.SeasonPremiere -> "NEW SEASON"
    CalendarBadge.Finale -> "FINALE"
    CalendarBadge.Digital -> "DIGITAL"
    CalendarBadge.Physical -> "PHYSICAL"
    else -> null
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
