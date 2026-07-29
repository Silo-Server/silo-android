package org.siloserver.silo.android.ui.screens.home

import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.section.toBrowseHeroMetadata

internal enum class FeaturedHeroMetadataKind {
    Plain,
    Rating,
    Classification,
}

internal data class FeaturedHeroMetadataChip(
    val label: String,
    val kind: FeaturedHeroMetadataKind = FeaturedHeroMetadataKind.Plain,
)

internal fun featuredHeroMaxGenres(screenWidthDp: Int): Int =
    if (screenWidthDp >= 600) 2 else 1

internal fun featuredHeroMetadata(
    item: SectionItem,
    maxGenres: Int,
): List<FeaturedHeroMetadataChip> {
    val metadata = item.toBrowseHeroMetadata(maxGenres)
    return buildList {
        metadata.leadingToken?.let { add(FeaturedHeroMetadataChip(it)) }
        metadata.runtimeToken?.let { add(FeaturedHeroMetadataChip(it)) }
        metadata.imdbRatingToken?.let {
            add(FeaturedHeroMetadataChip(it, FeaturedHeroMetadataKind.Rating))
        }
        metadata.genres.forEach { add(FeaturedHeroMetadataChip(it)) }
        metadata.contentRating?.let {
            add(FeaturedHeroMetadataChip(it, FeaturedHeroMetadataKind.Classification))
        }
    }
}
