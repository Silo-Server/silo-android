package org.siloserver.silo.android.ui.screens.home

import java.util.Locale
import kotlin.math.roundToInt
import org.siloserver.silo.model.section.SectionItem

internal enum class FeaturedHeroMetadataKind {
    Plain,
    Rating,
    Classification,
}

internal data class FeaturedHeroMetadataChip(
    val label: String,
    val kind: FeaturedHeroMetadataKind = FeaturedHeroMetadataKind.Plain,
)

internal fun featuredHeroMetadata(item: SectionItem): List<FeaturedHeroMetadataChip> {
    val result = mutableListOf<FeaturedHeroMetadataChip>()
    val isEpisode = item.type.equals("episode", ignoreCase = true)

    if (isEpisode) {
        episodeToken(item.seasonNumber, item.episodeNumber)?.let {
            result += FeaturedHeroMetadataChip(it)
        }
    } else if (item.year > 0) {
        result += FeaturedHeroMetadataChip(item.year.toString())
    }

    formatFeaturedRuntime(item.runtime, item.durationSeconds)?.let {
        result += FeaturedHeroMetadataChip(it)
    }
    validImdbRating(item.ratingImdb)
        ?.let {
            result += FeaturedHeroMetadataChip(
                label = String.format(Locale.US, "%.1f", it),
                kind = FeaturedHeroMetadataKind.Rating,
            )
        }
    if (!isEpisode) {
        item.genres.firstOrNull { it.isNotBlank() }?.let {
            result += FeaturedHeroMetadataChip(it)
        }
    }
    item.contentRating
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.US)
        ?.let {
            result += FeaturedHeroMetadataChip(
                label = it,
                kind = FeaturedHeroMetadataKind.Classification,
            )
        }
    return result
}

private fun validImdbRating(rating: Double?): Double? =
    rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }

private fun episodeToken(season: Int?, episode: Int?): String? = when {
    season != null && episode != null -> "S$season E$episode"
    season != null -> "Season $season"
    episode != null -> "Episode $episode"
    else -> null
}

/** Episode/movie length: the metadata runtime when present, else derived
 *  from the file duration the payload already carries. */
private fun formatFeaturedRuntime(runtimeMinutes: Int?, durationSeconds: Double?): String? {
    runtimeMinutes?.takeIf { it > 0 }?.let { return formatRuntimeMinutes(it) }
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
        ?: return null
    val minutes = (duration / 60.0).roundToInt().takeIf { it > 0 }
        ?: return null
    return formatRuntimeMinutes(minutes)
}

private fun formatRuntimeMinutes(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) "${hours}h" else "${hours}h ${remainder}m"
}
