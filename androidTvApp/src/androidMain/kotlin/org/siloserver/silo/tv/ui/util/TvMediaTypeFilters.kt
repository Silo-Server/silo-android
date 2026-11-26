package org.siloserver.silo.tv.ui.util

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.navigation.isAudiobookLikeLibraryType
import org.siloserver.silo.model.navigation.isEbookLikeLibraryType
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.viewmodel.ItemCollectionsRow
import kotlin.jvm.JvmName

/**
 * TV hides ebook-like content (no reader on TV) but keeps audiobooks.
 * Both predicates delegate to the shared reading taxonomy in MediaMode.kt
 * so the type sets cannot drift between platforms; only the TV-specific
 * hide/keep decision lives here.
 */
fun isTvHiddenMediaType(type: String?): Boolean = isEbookLikeLibraryType(type)

fun isAudiobookMediaType(type: String?): Boolean = isAudiobookLikeLibraryType(type)

fun tvArtworkAspectRatioForMediaType(type: String?): Float? =
    if (isAudiobookMediaType(type)) 1f else null

@JvmName("browseItemsVisibleOnTv")
fun Iterable<BrowseItem>.visibleOnTv(): List<BrowseItem> =
    filterNot { isTvHiddenMediaType(it.type) }

@JvmName("userLibrariesVisibleOnTv")
fun Iterable<UserLibrary>.visibleOnTv(): List<UserLibrary> =
    filterNot { isTvHiddenMediaType(it.type) }

/**
 * Rows with something TV shows. A row of collections keeps its cards: they
 * name no media type, and Home drops ebook libraries' cards where it knows the
 * library's type.
 */
@JvmName("resolvedSectionsVisibleOnTv")
fun Iterable<ResolvedSection>.visibleOnTv(): List<ResolvedSection> =
    mapNotNull { section ->
        if (section.isCollectionRow) return@mapNotNull section.takeIf { it.collections.isNotEmpty() }
        val visibleItems = section.items.filterNot { isTvHiddenMediaType(it.type) }
        section.takeIf { visibleItems.isNotEmpty() }?.copy(items = visibleItems)
    }

/**
 * Catalog `mediaType` to request when browsing a library of [type]. Returns
 * null for types that don't map to a known catalog media type (e.g. music/
 * audio) so the browse is scoped by `libraryId` alone instead of being wrongly
 * forced to "movie" — which previously hid every item in a music library.
 */
fun tvCatalogMediaTypeFor(type: String): String? = when (type.lowercase()) {
    "movie", "movies" -> "movie"
    "series", "shows", "tv" -> "series"
    "audiobook", "audiobooks" -> "audiobook"
    else -> null
}

/**
 * A title page's collections row as TV shows it: a preview without reading
 * titles. A preview left with nothing shows its collection's card under
 * "Also in", so the collection can still be opened.
 */
fun ItemCollectionsRow.visibleOnTv(): ItemCollectionsRow = when (this) {
    is ItemCollectionsRow.AlsoIn -> this
    is ItemCollectionsRow.Preview -> {
        val shown = items.visibleOnTv()
        if (shown.isEmpty()) ItemCollectionsRow.AlsoIn(listOf(collection)) else copy(items = shown)
    }
}

/**
 * The collections row with only the server collections whose library TV
 * shows ([shownLibraryIds]; never an ebook library), as Home keeps them.
 * Personal collections always stay. Null when none is left.
 */
fun ItemCollectionsRow.withTvShownCollections(shownLibraryIds: Set<Int>): ItemCollectionsRow? {
    fun org.siloserver.silo.model.section.SectionCollectionCard.shown() =
        isUserCollection || libraryIdInt in shownLibraryIds
    return when (this) {
        is ItemCollectionsRow.Preview -> takeIf { collection.shown() }
        is ItemCollectionsRow.AlsoIn -> cards.filter { it.shown() }.takeIf { it.isNotEmpty() }?.let(ItemCollectionsRow::AlsoIn)
    }
}
