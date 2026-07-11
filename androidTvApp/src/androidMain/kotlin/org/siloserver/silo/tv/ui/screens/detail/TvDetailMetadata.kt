package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.isAudiobookItemType
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

    fun factsLine(
        detail: ItemDetail,
        preferredQuality: String? = null,
        selectedFileId: Int? = null,
    ): List<TvHeroFactToken> {
        val tokens = mutableListOf<TvHeroFactToken>()
        if (detail.type.equals("episode", ignoreCase = true)) {
            abbreviatedDate(detail.airDate ?: detail.releaseDate)?.let {
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
        tokens += qualityTokens(detail, preferredQuality, selectedFileId)
        return tokens
    }

    fun eyebrow(detail: ItemDetail): String? {
        if (detail.type.equals("episode", ignoreCase = true)) {
            return detail.seriesTitle?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (detail.type.equals("season", ignoreCase = true)) {
            // tvOS `TVSeasonDetailView`: the season hero eyebrow carries the
            // parent-series title.
            return detail.seriesTitle?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (detail.type.equals("series", ignoreCase = true)) {
            when (detail.status?.trim()?.lowercase()) {
                "continuing", "returning series", "returning" -> return "Continuing Series"
                "ended" -> return "Complete Series"
                "in production" -> return "New Season Coming"
                else -> Unit
            }
        }
        val tagline = detail.tagline?.trim()?.takeIf { it.isNotEmpty() }
        if (tagline != null && tagline.length <= 44) return tagline
        return null
    }

    fun starringText(detail: ItemDetail): String? {
        val names = detail.cast.take(3).map { it.name.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        return "Starring ${names.joinToString(", ")}"
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

    /** tvOS `DetailDateFormatting.abbreviatedDate`: stable medium-style date
     *  for episode hero facts, tolerant of full RFC3339 timestamps. */
    private fun abbreviatedDate(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val date = trimmed.take(10)
        val parts = date.split("-")
        if (parts.size != 3) return trimmed
        val year = parts[0].toIntOrNull() ?: return trimmed
        val month = parts[1].toIntOrNull() ?: return trimmed
        val day = parts[2].toIntOrNull() ?: return trimmed
        val monthName = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        ).getOrNull(month - 1) ?: return trimmed
        return "$monthName $day, $year"
    }

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
