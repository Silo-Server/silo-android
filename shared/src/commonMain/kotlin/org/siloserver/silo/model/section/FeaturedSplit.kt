package org.siloserver.silo.model.section

data class FeaturedSplit(
    val featured: ResolvedSection?,
    val rest: List<ResolvedSection>,
)

/**
 * Pulls the first non-empty section flagged `featured` out of the list and
 * returns it alongside the remaining sections in their original order.
 * The featured section is filtered out of `rest` so callers can render it
 * once (as a hero) without it also appearing as a regular row.
 *
 * Sections with empty `items` lists never qualify as featured because
 * there's nothing to render in the hero.
 */
fun List<ResolvedSection>.splitFeatured(): FeaturedSplit {
    val featured = firstOrNull { it.featured && it.items.isNotEmpty() }
    val rest = if (featured == null) this else filter { it.id != featured.id }
    return FeaturedSplit(featured, rest)
}

/**
 * Promotes the server's top Home row only when that exact row is non-empty and
 * featured. Featured rows farther down stay in [FeaturedSplit.rest], preserving
 * the ordering configured in the web admin UI.
 */
fun List<ResolvedSection>.splitTopFeatured(): FeaturedSplit {
    val featured = firstOrNull()?.takeIf { it.featured && it.items.isNotEmpty() }
    val rest = if (featured == null) this else drop(1)
    return FeaturedSplit(featured, rest)
}
