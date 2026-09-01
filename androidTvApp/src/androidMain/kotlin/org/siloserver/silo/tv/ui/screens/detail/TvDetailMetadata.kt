package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.isAudiobookItemType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.round

/**
 * Pure functions that build the hero metadata for the cinematic detail
 * layout. Mirrors `TVHeroMetadata` from the tvOS app — the same source of
 * truth lives in two languages so the two clients stay visually aligned.
 */
internal object TvDetailMetadata {

    fun sourceTokens(detail: ItemDetail): List<String> = when {
        detail.type.equals("episode", ignoreCase = true) -> buildList {
            episodeNumberLabel(detail)?.let { add(it) }
            detail.genres.firstOrNull { it.isNotBlank() }?.let { add(it) }
        }
        detail.type.equals("series", ignoreCase = true) -> buildList {
            add("TV Show")
            detail.genres.filter { it.isNotBlank() }.take(2).forEach { add(it) }
        }
        detail.type.equals("season", ignoreCase = true) -> buildList {
            // tvOS `TVSeasonDetailView.sourceTokens`: leading episode-count token,
            // then up to two genres.
            detail.episodeCount?.takeIf { it > 0 }?.let {
                add("$it Episode${if (it == 1) "" else "s"}")
            }
            detail.genres.filter { it.isNotBlank() }.take(2).forEach { add(it) }
        }
        isAudiobookItemType(detail.type) -> listOfNotNull(
            "Audiobook",
            detail.audiobook?.publisher,
            detail.audiobook?.narratorNames?.let { "Narrated by $it" },
        )
        else -> buildList {
            add(typeLabel(detail))
            detail.genres.filter { it.isNotBlank() }.take(2).forEach { add(it) }
        }
    }

    fun ratingChip(detail: ItemDetail): String? =
        detail.contentRating?.trim()?.takeIf { it.isNotEmpty() }

    fun seriesEpisodeSourceTokens(episode: EpisodeListItem): List<String> = listOfNotNull(
        if (episode.seasonNumber == 0) "Specials" else "Season ${episode.seasonNumber}",
        "Episode ${episode.episodeNumber}".takeIf { episode.episodeNumber > 0 },
    )

    /** Episode editorial facts used by the combined Series page. These mirror
     * tvOS: the episode's air date and runtime replace the Show facts, while
     * version choices remain directly beneath the episode carousel. */
    fun seriesEpisodeFactsLine(
        episode: EpisodeListItem,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TvHeroFactToken> = buildList {
        abbreviatedDate(episode.airDate, zone)?.let {
            add(TvHeroFactToken.TextToken(it))
        }
        runtimeLabel(episode.runtime)?.let { add(TvHeroFactToken.TextToken(it)) }
    }

    fun factsLine(
        detail: ItemDetail,
        preferredQuality: String? = null,
        selectedFileId: Int? = null,
        includePlaybackFormats: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TvHeroFactToken> {
        val tokens = mutableListOf<TvHeroFactToken>()
        if (detail.type.equals("episode", ignoreCase = true)) {
            abbreviatedDate(detail.airDate ?: detail.releaseDate, zone)?.let {
                tokens += TvHeroFactToken.TextToken(it)
            }
        } else if (detail.year > 0) {
            tokens += TvHeroFactToken.TextToken(detail.year.toString())
        }
        when {
            detail.type.equals("series", ignoreCase = true) ->
                detail.seasonCount?.takeIf { it > 0 }?.let {
                    tokens += TvHeroFactToken.TextToken("$it Season${if (it == 1) "" else "s"}")
                }
            else ->
                runtimeLabel(detail.runtime)?.let { tokens += TvHeroFactToken.TextToken(it) }
        }
        detail.ratingImdb?.let {
            tokens += TvHeroFactToken.TextToken("★ ${formatOneDecimal(it)}")
        }
        if (includePlaybackFormats) {
            tokens += qualityTokens(detail, preferredQuality, selectedFileId)
        }
        return tokens
    }

    private fun typeLabel(detail: ItemDetail): String = when {
        isAudiobookItemType(detail.type) -> "Audiobook"
        else -> when (detail.type.lowercase()) {
            "movie" -> "Movie"
            "series" -> "Series"
            "season" -> "Season"
            "episode" -> "Episode"
            else -> detail.type.replaceFirstChar { it.titlecase() }
        }
    }

    private fun episodeNumberLabel(detail: ItemDetail): String? {
        val seasonPart = detail.seasonNumber?.let { n ->
            if (n == 0) "Specials" else "Season $n"
        }
        val episodePart = detail.episodeNumber?.takeIf { it > 0 }?.let { "Episode $it" }
        return when {
            seasonPart != null && episodePart != null -> "$seasonPart · $episodePart"
            seasonPart != null -> seasonPart
            episodePart != null -> episodePart
            else -> null
        }
    }

    private fun runtimeLabel(minutes: Int): String? {
        if (minutes <= 0) return null
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
    }

    /**
     * tvOS `DetailDateFormatting.abbreviatedDate`: parse the server value as a
     * UTC instant (its ISO8601 parsers all assume GMT — including bare
     * `yyyy-MM-dd` dates) and render the DEVICE-LOCAL calendar date. Keeping
     * that quirk is deliberate: for a viewer west of UTC both clients show
     * e.g. "Jul 8" for a `2026-07-09T00:00:00Z` air date, instead of the TV
     * app drifting a day ahead of tvOS.
     */
    internal fun abbreviatedDate(raw: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val instant = parseServerInstant(trimmed) ?: return trimmed
        val date = instant.atZone(zone).toLocalDate()
        val monthName = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        ).getOrNull(date.monthValue - 1) ?: return trimmed
        return "$monthName ${date.dayOfMonth}, ${date.year}"
    }

    /** RFC3339 timestamp (with/without fraction or offset) or bare full date,
     *  as UTC — the same parser cascade as Apple's `DetailDateFormatting`. */
    private fun parseServerInstant(raw: String): Instant? =
        runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()

    private fun formatOneDecimal(value: Double): String {
        val rounded = round(value * 10.0) / 10.0
        val whole = rounded.toInt()
        val tenths = (round((rounded - whole) * 10.0)).toInt().coerceIn(0, 9)
        return "$whole.$tenths"
    }

    private fun qualityTokens(
        detail: ItemDetail,
        preferredQuality: String?,
        selectedFileId: Int?,
    ): List<TvHeroFactToken> {
        val version = preferredVersion(detail, preferredQuality, selectedFileId) ?: return emptyList()
        val tokens = mutableListOf<TvHeroFactToken>()
        resolutionLabel(version.resolution)?.let { tokens += TvHeroFactToken.Chip(it) }
        when {
            TvPlaybackFormatting.isDolbyVision(version) ->
                tokens += TvHeroFactToken.Chip("DOLBY VISION")
            TvPlaybackFormatting.isHdr(version) ->
                tokens += TvHeroFactToken.Chip("HDR")
        }
        primaryAudioLabel(version)?.let { tokens += TvHeroFactToken.Chip(it) }
        if (hasSubtitles(version, detail)) tokens += TvHeroFactToken.Chip("CC")
        return tokens
    }

    private fun preferredVersion(
        detail: ItemDetail,
        preferredQuality: String?,
        selectedFileId: Int?,
    ): FileVersion? {
        return selectTvDetailDisplayVersion(
            versions = detail.versions,
            selectedFileId = selectedFileId,
            lastFileId = detail.userData?.lastFileId,
            preferredQuality = preferredQuality,
        )
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

    private fun hasSubtitles(version: FileVersion, detail: ItemDetail): Boolean {
        val versionSubs = version.subtitleTracks
        if (!versionSubs.isNullOrEmpty()) return true
        return detail.subtitles.isNotEmpty()
    }
}
