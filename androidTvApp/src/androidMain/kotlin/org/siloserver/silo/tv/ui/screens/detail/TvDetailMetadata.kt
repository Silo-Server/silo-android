package org.siloserver.silo.tv.ui.screens.detail

import java.util.Locale
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.getStringWithNames
import org.siloserver.silo.model.catalog.DisplayRating
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.catalog.selectedMediaRuntimeMinutes
import org.siloserver.silo.model.catalog.titleRatings
import org.siloserver.silo.tv.ui.components.TvHeroFactToken
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import android.content.res.Resources
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleInteger

/**
 * Pure functions that build the hero metadata for the cinematic detail
 * layout. Mirrors `TVHeroMetadata` from the tvOS app — the same source of
 * truth lives in two languages so the two clients stay visually aligned.
 */
internal object TvDetailMetadata {

    fun sourceTokens(resources: Resources, detail: ItemDetail): List<String> = when {
        detail.type.equals("episode", ignoreCase = true) -> buildList {
            episodeNumberLabel(resources, detail)?.let { add(it) }
            detail.genres.firstOrNull { it.isNotBlank() }?.let { add(it) }
        }
        detail.type.equals("series", ignoreCase = true) -> buildList {
            add(resources.getString(CommonR.string.detail_type_tvShow))
            detail.genres.filter { it.isNotBlank() }.take(2).forEach { add(it) }
        }
        detail.type.equals("season", ignoreCase = true) -> buildList {
            // tvOS `TVSeasonDetailView.sourceTokens`: leading episode-count token,
            // then up to two genres.
            detail.episodeCount?.takeIf { it > 0 }?.let {
                add(resources.getQuantityString(CommonR.plurals.detail_seasonEpisodeCount, it, it, LocaleFormat.integer(it)))
            }
            detail.genres.filter { it.isNotBlank() }.take(2).forEach { add(it) }
        }
        isAudiobookItemType(detail.type) -> listOfNotNull(
            resources.getString(CommonR.string.detail_type_audiobook),
            detail.audiobook?.publisher,
            detail.audiobook?.narratorNames?.let { resources.getStringWithNames(CommonR.string.detail_narratedBy, it) },
        )
        else -> buildList {
            add(typeLabel(resources, detail))
            detail.genres.filter { it.isNotBlank() }.take(2).forEach { add(it) }
        }
    }

    fun ratingChip(detail: ItemDetail): String? =
        detail.contentRating?.trim()?.takeIf { it.isNotEmpty() }

    fun seriesEpisodeSourceTokens(resources: Resources, episode: EpisodeListItem): List<String> = listOfNotNull(
        seasonLabel(resources, episode.seasonNumber),
        resources.getString(CommonR.string.detail_episode, LocaleFormat.integer(episode.episodeNumber))
            .takeIf { episode.episodeNumber > 0 },
    )

    /** Episode editorial facts used by the combined Series page. These mirror
     * tvOS: the episode's air date and runtime replace the Show facts, while
     * version choices remain directly beneath the episode carousel. The Show's
     * [ratings] stay, like its content-rating chip, so focusing an episode
     * never drops the ratings row. */
    fun seriesEpisodeFactsLine(
        resources: Resources,
        episode: EpisodeListItem,
        runtimeMinutes: Int = episode.runtime,
        ratings: List<DisplayRating> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TvHeroFactToken> = buildList {
        abbreviatedDate(episode.airDate, zone)?.let {
            add(TvHeroFactToken.TextToken(it))
        }
        runtimeLabel(resources, runtimeMinutes)?.let { add(TvHeroFactToken.TextToken(it)) }
        ratings.forEach { add(TvHeroFactToken.ExternalRating(it)) }
    }

    fun factsLine(
        resources: Resources,
        detail: ItemDetail,
        preferredQuality: String? = null,
        selectedFileId: Int? = null,
        selectedEditionId: String? = null,
        includePlaybackFormats: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TvHeroFactToken> {
        val tokens = mutableListOf<TvHeroFactToken>()
        val selectedVersion = selectTvDetailDisplayVersion(detail, selectedFileId, selectedEditionId, preferredQuality)
        if (detail.type.equals("episode", ignoreCase = true)) {
            abbreviatedDate(detail.airDate ?: detail.releaseDate, zone)?.let {
                tokens += TvHeroFactToken.TextToken(it)
            }
        } else if (detail.year > 0) {
            tokens += TvHeroFactToken.NumberToken(LocaleInteger(detail.year))
        }
        when {
            detail.type.equals("series", ignoreCase = true) ->
                detail.seasonCount?.takeIf { it > 0 }?.let {
                    tokens += TvHeroFactToken.TextToken(
                        resources.getQuantityString(CommonR.plurals.catalog_seasons, it, it),
                    )
                }
            else ->
                runtimeLabel(resources, selectedMediaRuntimeMinutes(detail, selectedVersion))?.let {
                    tokens += TvHeroFactToken.TextToken(it)
                }
        }
        detail.titleRatings().forEach { tokens += TvHeroFactToken.ExternalRating(it) }
        if (includePlaybackFormats) {
            tokens += qualityTokens(selectedVersion)
        }
        return tokens
    }

    private fun typeLabel(resources: Resources, detail: ItemDetail): String = when {
        isAudiobookItemType(detail.type) -> resources.getString(CommonR.string.detail_type_audiobook)
        else -> when (detail.type.lowercase()) {
            "movie" -> resources.getString(CommonR.string.detail_type_movie)
            "series" -> resources.getString(CommonR.string.detail_type_series)
            "season" -> resources.getString(CommonR.string.detail_type_season)
            "episode" -> resources.getString(CommonR.string.detail_type_episode)
            else -> detail.type.replaceFirstChar { it.titlecase() }
        }
    }

    private fun seasonLabel(resources: Resources, seasonNumber: Int): String =
        if (seasonNumber == 0) {
            resources.getString(CommonR.string.detail_specials)
        } else {
            resources.getString(CommonR.string.detail_season, LocaleFormat.integer(seasonNumber))
        }

    private fun episodeNumberLabel(resources: Resources, detail: ItemDetail): String? {
        val seasonPart = detail.seasonNumber?.let { n -> seasonLabel(resources, n) }
        val episodePart = detail.episodeNumber?.takeIf { it > 0 }?.let {
            resources.getString(CommonR.string.detail_episode, LocaleFormat.integer(it))
        }
        return when {
            seasonPart != null && episodePart != null -> "$seasonPart · $episodePart"
            seasonPart != null -> seasonPart
            episodePart != null -> episodePart
            else -> null
        }
    }

    private fun runtimeLabel(resources: Resources, minutes: Int): String? {
        if (minutes <= 0) return null
        return if (minutes >= 60) {
            resources.getString(
                CommonR.string.catalog_runtimeHoursMinutes,
                LocaleFormat.integer(minutes / 60),
                LocaleFormat.integer(minutes % 60),
            )
        } else {
            resources.getString(CommonR.string.catalog_runtimeMinutes, LocaleFormat.integer(minutes))
        }
    }

    /**
     * tvOS `DetailDateFormatting.abbreviatedDate`: the server's air and release
     * dates are calendar dates (`yyyy-MM-dd`) and show that day in every time
     * zone. An RFC 3339 timestamp converts to its calendar date in [zone].
     * "Jul 8, 2026" in US English, in [locale]'s order elsewhere.
     */
    internal fun abbreviatedDate(
        raw: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = LocaleFormat.locale(),
    ): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val date = parseServerDate(trimmed, zone) ?: return trimmed
        return date.format(LocaleFormat.dateFormatter("yMMMd", "MMM d, y", locale, padYear = false))
    }

    /** A bare `yyyy-MM-dd` as that day, or an RFC 3339 timestamp (`Z` or a
     *  numeric offset, with or without a fraction) as its date in [zone]. */
    private fun parseServerDate(raw: String, zone: ZoneId): LocalDate? =
        runCatching { LocalDate.parse(raw) }.getOrNull()
            ?: runCatching { Instant.parse(raw).atZone(zone).toLocalDate() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDate() }.getOrNull()

    private fun qualityTokens(version: FileVersion?): List<TvHeroFactToken> {
        version ?: return emptyList()
        val tokens = mutableListOf<TvHeroFactToken>()
        resolutionLabel(version.resolution)?.let { tokens += TvHeroFactToken.Chip(it) }
        when {
            TvPlaybackFormatting.isDolbyVision(version) ->
                tokens += TvHeroFactToken.Chip("DOLBY VISION")
            TvPlaybackFormatting.isHdr(version) ->
                tokens += TvHeroFactToken.Chip("HDR")
        }
        primaryAudioLabel(version)?.let { tokens += TvHeroFactToken.Chip(it) }
        if (!version.subtitleTracks.isNullOrEmpty()) tokens += TvHeroFactToken.Chip("CC")
        return tokens
    }

    private fun resolutionLabel(raw: String?): String? {
        val v = raw?.lowercase() ?: return null
        return when {
            "2160" in v || "4k" in v -> "4K"
            "1080" in v -> "HD"
            "720" in v -> "HD"
            "480" in v -> "SD"
            else -> null
        }
    }

    private fun primaryAudioLabel(version: FileVersion): String? {
        val tracks = version.audioTracks ?: return null
        val track = tracks.firstOrNull { it.isDefault } ?: tracks.firstOrNull() ?: return null
        track.channelLayout?.lowercase()?.let { layout ->
            if ("atmos" in layout) return "ATMOS"
            if ("7.1" in layout) return "7.1"
            if ("5.1" in layout) return "5.1"
            if ("stereo" in layout || layout == "2.0") return null
        }
        return when (track.channels) {
            8 -> "7.1"
            6 -> "5.1"
            else -> null
        }
    }

}
