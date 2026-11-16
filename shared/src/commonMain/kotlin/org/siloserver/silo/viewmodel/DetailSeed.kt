package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.catalog.isBookLikeItemType
import org.siloserver.silo.model.catalog.isEpisodeItemType
import org.siloserver.silo.model.section.SectionItem
import kotlinx.coroutines.flow.StateFlow

/**
 * The card a detail page is being opened from. A card sets it on tap; the
 * page's view model takes it once, so the page can draw the card's title,
 * facts and artwork while its detail request is in flight. Mirrors Apple's
 * `ItemDetail.seed` (DetailSeed.swift).
 *
 * It holds only the last tap, and only for the account and profile it was
 * made under: a tap that did not navigate cannot paint a page opened after
 * a sign-in or profile change.
 */
object DetailSeeds {
    private class Pending(val seedFor: (String) -> ItemDetail?, val identity: Long?)

    @kotlin.concurrent.Volatile
    private var identity: StateFlow<Long>? = null

    @kotlin.concurrent.Volatile
    private var pending: Pending? = null

    /** Ties taps to the identity generation; the catalog repository binds it at startup. */
    fun bindIdentity(generation: StateFlow<Long>) {
        identity = generation
    }

    /** A Home or row card was tapped. Building the seed waits for [take]. */
    fun offer(item: SectionItem) = offer { detailSeed(item, it) }

    /** A catalog card was tapped. */
    fun offer(item: BrowseItem) = offer { detailSeed(item, it) }

    private fun offer(seedFor: (String) -> ItemDetail?) {
        pending = Pending(seedFor, identity?.value)
    }

    /**
     * The seed for the page opening [contentId], if the last tap opened it
     * under the current identity. Clears the slot either way.
     */
    fun take(contentId: String): ItemDetail? {
        val tap = pending ?: return null
        pending = null
        return if (tap.identity == identity?.value) tap.seedFor(contentId) else null
    }
}

/**
 * A provisional detail drawn from the card that opens [opening]. Fields a
 * card does not carry (files, watch state, cast, crew, chapters, authors)
 * stay empty; the page keeps the actions that need them unavailable until
 * the fetched detail replaces the seed.
 *
 * A movie, series, audiobook or book card seeds its own page. An episode
 * card that opens its series seeds only the series' id, title and artwork,
 * never the episode's description, still or runtime. Anything else, or a
 * card without a title, seeds nothing.
 */
fun detailSeed(item: SectionItem, opening: String): ItemDetail? =
    if (item.contentId == opening) {
        seedType(item.type)?.takeIf { item.title.isNotBlank() }?.let { type ->
            ItemDetail(
                contentId = opening,
                type = type,
                status = item.status,
                title = item.title,
                originalLanguage = item.originalLanguage,
                showStatus = item.showStatus,
                year = item.year,
                overview = item.overview,
                machineTranslatedFields = item.machineTranslatedFields,
                runtime = item.runtime ?: 0,
                contentRating = item.contentRating,
                genres = item.genres,
                ratingImdb = item.ratingImdb,
                ratingTmdb = item.ratingTmdb,
                ratingRtCritic = item.ratingRtCritic,
                ratingRtAudience = item.ratingRtAudience,
                studios = item.studios,
                networks = item.networks,
                posterIsEpisodeStill = item.posterIsEpisodeStill,
                backdropIsEpisodeStill = item.backdropIsEpisodeStill,
                posterUrl = item.posterUrl,
                posterThumbhash = item.posterThumbhash,
                backdropUrl = item.backdropUrl,
                backdropThumbhash = item.backdropThumbhash,
                logoUrl = item.logoUrl,
                overlaySummary = item.overlaySummary,
            )
        }
    } else {
        seriesSeedFromEpisode(
            type = item.type,
            seriesId = item.seriesId,
            seriesTitle = item.seriesTitle,
            opening = opening,
            posterUrl = item.seriesPosterUrl,
            posterThumbhash = item.seriesPosterThumbhash,
            backdropUrl = item.seriesBackdropUrl,
            backdropThumbhash = item.seriesBackdropThumbhash,
        )
    }

/** [detailSeed] for a catalog card. */
fun detailSeed(item: BrowseItem, opening: String): ItemDetail? =
    if (item.contentId == opening) {
        seedType(item.type)?.takeIf { item.title.isNotBlank() }?.let { type ->
            ItemDetail(
                contentId = opening,
                type = type,
                status = item.status,
                title = item.title,
                originalLanguage = item.originalLanguage,
                showStatus = item.showStatus,
                year = item.year,
                overview = item.overview,
                machineTranslatedFields = item.machineTranslatedFields,
                runtime = item.runtime ?: 0,
                contentRating = item.contentRating,
                genres = item.genres,
                ratingImdb = item.ratingImdb,
                ratingTmdb = item.ratingTmdb,
                ratingRtCritic = item.ratingRtCritic,
                ratingRtAudience = item.ratingRtAudience,
                studios = item.studios,
                networks = item.networks,
                releaseDate = item.releaseDate,
                lastAirDate = item.lastAirDate,
                posterIsEpisodeStill = item.posterIsEpisodeStill,
                backdropIsEpisodeStill = item.backdropIsEpisodeStill,
                posterUrl = item.posterUrl,
                posterThumbhash = item.posterThumbhash,
                backdropUrl = item.backdropUrl,
                backdropThumbhash = item.backdropThumbhash,
                overlaySummary = item.overlaySummary,
            )
        }
    } else {
        seriesSeedFromEpisode(
            type = item.type,
            seriesId = item.seriesId,
            seriesTitle = item.seriesTitle,
            opening = opening,
            posterUrl = item.seriesPosterUrl,
            posterThumbhash = item.seriesPosterThumbhash,
            backdropUrl = item.seriesBackdropUrl,
            backdropThumbhash = item.seriesBackdropThumbhash,
        )
    }

/** The page type a card opens on its own; null for episodes and seasons, which open their series. */
private fun seedType(cardType: String): String? {
    val type = cardType.trim().lowercase()
    return when {
        type == "movie" || type == "series" -> type
        isAudiobookItemType(type) || isBookLikeItemType(type) -> type
        else -> null
    }
}

/**
 * The card's own poster and backdrop may be the episode's, so only the
 * artwork the server marks as the series' is used.
 */
private fun seriesSeedFromEpisode(
    type: String,
    seriesId: String?,
    seriesTitle: String?,
    opening: String,
    posterUrl: String?,
    posterThumbhash: String?,
    backdropUrl: String?,
    backdropThumbhash: String?,
): ItemDetail? {
    if (!isEpisodeItemType(type) || seriesId != opening || seriesTitle.isNullOrBlank()) return null
    val poster = posterUrl?.takeIf { it.isNotBlank() }
    val backdrop = backdropUrl?.takeIf { it.isNotBlank() }
    return ItemDetail(
        contentId = opening,
        type = "series",
        title = seriesTitle,
        posterUrl = poster,
        posterThumbhash = posterThumbhash.takeIf { poster != null },
        backdropUrl = backdrop,
        backdropThumbhash = backdropThumbhash.takeIf { backdrop != null },
    )
}
