package org.siloserver.silo.model.catalog

import org.siloserver.silo.model.section.SectionItem

/** One image and its ThumbHash placeholder. */
data class ArtworkRef(val url: String, val thumbhash: String?)

/**
 * The series backdrop an episode card carries (`series_backdrop_url`), for
 * heroes and full-screen backgrounds. An episode card's own `backdrop_url` is
 * usually the episode still, which looks soft blown up. Series artwork is never
 * a still, so spoiler protection leaves it visible. Null for other types and on
 * servers without `episode_parent_artwork`, where callers keep their old path.
 */
fun SectionItem.seriesHeroBackdrop(): ArtworkRef? =
    episodeSeriesBackdrop(type, seriesBackdropUrl, seriesBackdropThumbhash)

fun BrowseItem.seriesHeroBackdrop(): ArtworkRef? =
    episodeSeriesBackdrop(type, seriesBackdropUrl, seriesBackdropThumbhash)

fun ItemDetail.seriesHeroBackdrop(): ArtworkRef? =
    episodeSeriesBackdrop(type, seriesBackdropUrl, seriesBackdropThumbhash)

/** An episode's season poster, else its series poster; never an episode still. */
fun SectionItem.parentPoster(): ArtworkRef? =
    episodeParentPoster(type, seasonPosterUrl, seasonPosterThumbhash, seriesPosterUrl, seriesPosterThumbhash)

fun ItemDetail.parentPoster(): ArtworkRef? =
    episodeParentPoster(type, seasonPosterUrl, seasonPosterThumbhash, seriesPosterUrl, seriesPosterThumbhash)

private fun episodeSeriesBackdrop(type: String, url: String?, thumbhash: String?): ArtworkRef? {
    if (!type.equals("episode", ignoreCase = true)) return null
    return url?.takeIf { it.isNotBlank() }?.let { ArtworkRef(it, thumbhash) }
}

private fun episodeParentPoster(
    type: String,
    seasonUrl: String?,
    seasonThumbhash: String?,
    seriesUrl: String?,
    seriesThumbhash: String?,
): ArtworkRef? {
    if (!type.equals("episode", ignoreCase = true)) return null
    seasonUrl?.takeIf { it.isNotBlank() }?.let { return ArtworkRef(it, seasonThumbhash) }
    return seriesUrl?.takeIf { it.isNotBlank() }?.let { ArtworkRef(it, seriesThumbhash) }
}
