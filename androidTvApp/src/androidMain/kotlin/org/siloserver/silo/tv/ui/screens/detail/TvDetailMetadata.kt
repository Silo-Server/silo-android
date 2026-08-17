package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.isAudiobookItemType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure functions that build the hero metadata for the cinematic detail
 * layout. Mirrors `TVHeroMetadata` from the tvOS app — the same source of
 * truth lives in two languages so the two clients stay visually aligned.
 */
internal object TvDetailMetadata {

    private fun normalizedGenres(detail: ItemDetail): List<String> =
        detail.genres
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(2)

    fun sourceTokens(detail: ItemDetail): List<String> = when {
        detail.type.equals("episode", ignoreCase = true) -> buildList {
            episodeNumberLabel(detail)?.let(::add)
            addAll(normalizedGenres(detail))
        }
        detail.type.equals("series", ignoreCase = true) -> buildList {
            add("TV Show")
            addAll(normalizedGenres(detail))
        }
        detail.type.equals("season", ignoreCase = true) -> buildList {
            // tvOS `TVSeasonDetailView.sourceTokens`: leading episode-count token,
            // then up to two genres.
            detail.episodeCount?.takeIf { it > 0 }?.let {
                add("$it Episode${if (it == 1) "" else "s"}")
            }
            addAll(normalizedGenres(detail))
        }
        isAudiobookItemType(detail.type) -> listOfNotNull(
            "Audiobook",
            detail.audiobook?.publisher,
            detail.audiobook?.narratorNames?.let { "Narrated by $it" },
        )
        else -> buildList {
            add(typeLabel(detail))
            addAll(normalizedGenres(detail))
        }
    }

    fun ratingChip(detail: ItemDetail): String? =
        detail.contentRating
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase()

    fun factsLine(
        detail: ItemDetail,
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
        detail.ratingImdb
            ?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
            ?.let { tokens += TvHeroFactToken.TextToken("★ ${formatOneDecimal(it)}") }
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
        val tenths = (value * 10.0).roundToInt()
        return "${tenths / 10}.${abs(tenths % 10)}"
    }
}
