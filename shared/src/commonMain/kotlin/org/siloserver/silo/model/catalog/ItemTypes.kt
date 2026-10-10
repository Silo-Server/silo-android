package org.siloserver.silo.model.catalog

/**
 * Predicates over [ItemDetail.type] (catalog item types, always singular)
 * so detail screens stop hand-spelling type literals. Library-level
 * taxonomy (plural forms, `reading`, mode mapping) lives in
 * model/navigation/MediaMode.kt.
 */
private val bookLikeItemTypes = setOf(
    "book",
    "ebook",
    "comic",
    "manga",
)

private fun normalizedItemType(type: String?): String? =
    type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

fun isAudiobookItemType(type: String?): Boolean =
    normalizedItemType(type) == "audiobook"

fun isEpisodeItemType(type: String?): Boolean =
    normalizedItemType(type) == "episode"

private val tvItemTypes = setOf(
    "series",
    "season",
    "episode",
)

fun isTvItemType(type: String?): Boolean =
    normalizedItemType(type) in tvItemTypes

fun isBookLikeItemType(type: String?): Boolean =
    normalizedItemType(type) in bookLikeItemTypes

/**
 * Reading types for display only (the missing-artwork mark), which also
 * accepts the plurals some servers send. Routing stays on
 * [isBookLikeItemType], which takes the singular catalog types.
 */
private val bookDisplayTypes = bookLikeItemTypes + setOf(
    "ebooks",
    "comics",
)

fun isBookDisplayType(type: String?): Boolean =
    normalizedItemType(type) in bookDisplayTypes

private val podcastItemTypes = setOf(
    "podcast",
    "podcasts",
)

fun isPodcastItemType(type: String?): Boolean =
    normalizedItemType(type) in podcastItemTypes
