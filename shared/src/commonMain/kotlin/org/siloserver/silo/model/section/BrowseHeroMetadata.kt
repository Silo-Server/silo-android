package org.siloserver.silo.model.section

import kotlin.math.abs
import kotlin.math.roundToInt

data class BrowseHeroMetadata(
    val leadingToken: String?,
    val runtimeToken: String?,
    val imdbRatingToken: String?,
    val genres: List<String>,
    val contentRating: String?,
)

fun BrowseHeroMetadata.orderedTokens(): List<String> = buildList {
    leadingToken?.let(::add)
    runtimeToken?.let(::add)
    imdbRatingToken?.let(::add)
    addAll(genres)
}

fun SectionItem.toBrowseHeroMetadata(maxGenres: Int): BrowseHeroMetadata {
    require(maxGenres >= 0) { "maxGenres must be non-negative" }
    val isEpisode = type.equals("episode", ignoreCase = true)
    return BrowseHeroMetadata(
        leadingToken = if (isEpisode) {
            browseEpisodeToken(seasonNumber, episodeNumber)
        } else {
            year.takeIf { it > 0 }?.toString()
        },
        runtimeToken = browseRuntimeToken(runtime, durationSeconds),
        imdbRatingToken = browseRatingToken(ratingImdb),
        genres = if (isEpisode) {
            emptyList()
        } else {
            genres
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(maxGenres)
        },
        contentRating = contentRating
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase(),
    )
}

private fun browseEpisodeToken(season: Int?, episode: Int?): String? = when {
    season != null && episode != null -> "S$season E$episode"
    season != null -> "Season $season"
    episode != null -> "Episode $episode"
    else -> null
}

private fun browseRuntimeToken(runtimeMinutes: Int?, durationSeconds: Double?): String? {
    runtimeMinutes?.takeIf { it > 0 }?.let { return formatBrowseRuntime(it) }
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val roundedMinutes = (duration / 60.0).roundToInt().takeIf { it > 0 } ?: return null
    return formatBrowseRuntime(roundedMinutes)
}

private fun formatBrowseRuntime(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) "${hours}h" else "${hours}h ${remainder}m"
}

private fun browseRatingToken(rating: Double?): String? {
    val valid = rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 } ?: return null
    val tenths = (valid * 10.0).roundToInt()
    return "${tenths / 10}.${abs(tenths % 10)}"
}
