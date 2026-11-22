package org.siloserver.silo.model.catalog

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText

/**
 * How a surface spells an episode's season and episode numbers. Lists and
 * cards scan best with the narrow zero-padded [Tag]; the player, calendar and
 * detail screens use the roomier spellings.
 */
enum class EpisodeCodeStyle(
    internal val key: SharedString,
    internal val minDigits: Int,
    /** The catalog's English spelling, for [EpisodeLabel.englishText]. */
    private val englishSeparator: String,
) {
    /** "S01E02". */
    Tag(SharedString.EpisodeTag, 2, "E"),

    /** "S1E2", the tag a download stores. */
    Plain(SharedString.EpisodeTag, 1, "E"),

    /** "S1·E2". */
    Dotted(SharedString.EpisodeCodeDotted, 1, "·E"),

    /** "S1:E2". */
    Colon(SharedString.EpisodeCodeColon, 1, ":E"),

    /** "S1 · E2". */
    Spaced(SharedString.EpisodeCodeSpaced, 1, " · E"),
    ;

    internal fun english(season: String, episode: String): String = "S$season$englishSeparator$episode"
}

/**
 * The one place an episode's label is worded: its season and episode code, or,
 * for a series the server marks `episode_labels: air_date` (a sports league
 * Sportarr numbers S2026E296), the episode's air date ("Nov 20, 2026"). The
 * numbers still sort episodes and pick what plays next; only the label changes.
 * An undated episode keeps its code. Matches the web app's episodeCardLabels.
 */
object EpisodeLabel {
    /** The `episode_labels` value that labels a series' episodes by air date. */
    const val AIR_DATE = "air_date"

    /** Whether a series with these [episodeLabels] labels its episodes by air date. */
    fun labelsByAirDate(episodeLabels: String?): Boolean = episodeLabels == AIR_DATE

    /**
     * The air date that labels an episode of a date-labeled series, as a
     * calendar date (no time-zone shift), or null when the series uses numbers
     * or [airDate] is missing or not a valid `YYYY-MM-DD` date.
     */
    fun airDate(episodeLabels: String?, airDate: String?): UiText.CalendarDate? =
        if (labelsByAirDate(episodeLabels)) airDate?.let(::calendarDateOrNull) else null

    /**
     * "S01E02" (in [style]). A code is technical, so its numbers stay ASCII in
     * every language (D78).
     */
    fun code(seasonNumber: Int, episodeNumber: Int, style: EpisodeCodeStyle = EpisodeCodeStyle.Tag): UiText =
        UiText.Key(
            style.key,
            listOf(
                UiText.Raw(seasonNumber.toString().padStart(style.minDigits, '0')),
                UiText.Raw(episodeNumber.toString().padStart(style.minDigits, '0')),
            ),
        )

    /** The episode's [airDate] label for a date-labeled series, else its [code]. */
    fun text(
        seasonNumber: Int,
        episodeNumber: Int,
        episodeLabels: String?,
        airDate: String?,
        style: EpisodeCodeStyle = EpisodeCodeStyle.Tag,
    ): UiText = airDate(episodeLabels, airDate) ?: code(seasonNumber, episodeNumber, style)

    /**
     * [text] in English, for text that is data rather than interface: a Watch
     * Party item every member reads, a download's stored title, logs.
     * "Nov 20, 2026" or "S01E02".
     */
    fun englishText(
        seasonNumber: Int,
        episodeNumber: Int,
        episodeLabels: String?,
        airDate: String?,
        style: EpisodeCodeStyle = EpisodeCodeStyle.Tag,
    ): String = englishAirDate(episodeLabels, airDate)
        ?: style.english(
            seasonNumber.toString().padStart(style.minDigits, '0'),
            episodeNumber.toString().padStart(style.minDigits, '0'),
        )

    /** [airDate] in English ("Nov 20, 2026"), for data rather than interface; null as [airDate] is. */
    fun englishAirDate(episodeLabels: String?, airDate: String?): String? =
        airDate(episodeLabels, airDate)?.let { "${ENGLISH_MONTHS[it.month - 1]} ${it.day}, ${it.year}" }

    private val ENGLISH_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
