package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.ItemCollections
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.SectionCollectionCard
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.map
import org.siloserver.silo.repository.CatalogRepository

/** The collections row on a movie or series page. */
sealed interface ItemCollectionsRow {
    /**
     * One collection holds the title: some of its other titles under the
     * collection's name, with Explore all opening [collection].
     */
    data class Preview(val collection: SectionCollectionCard, val items: List<BrowseItem>) : ItemCollectionsRow

    /** An "Also in" row with a card for every collection holding the title. */
    data class AlsoIn(val cards: List<SectionCollectionCard>) : ItemCollectionsRow
}

/**
 * The collections row a movie or series page shows, or null for none. Only a
 * movie or series asks, and only a server that advertises `item_collections`
 * is asked, so older servers never see the read. The capability is read on
 * each load rather than cached, because it belongs to whichever server and
 * profile is current (as people search does). A failed read is returned as
 * is: the page shows no row on a first load, and a refresh keeps the row it
 * already shows.
 */
suspend fun loadItemCollectionsRow(
    catalogRepository: CatalogRepository,
    detail: ItemDetail,
    libraryId: Int?,
): ApiResult<ItemCollectionsRow?> {
    val type = detail.type.lowercase()
    if (type != "movie" && type != "series") return ApiResult.Success(null)
    val supported = when (val capabilities = catalogRepository.searchCapabilities()) {
        is ApiResult.Success -> capabilities.data.itemCollections
        is ApiResult.Error -> return capabilities
        is ApiResult.NetworkError -> return capabilities
    }
    if (!supported) return ApiResult.Success(null)
    return catalogRepository.getItemCollections(detail.contentId, libraryId).map(::itemCollectionsRow)
}

/** Titles a single collection's preview shows, as web does. */
const val ITEM_COLLECTION_PREVIEW_LIMIT: Int = 6

private fun itemCollectionsRow(collections: ItemCollections): ItemCollectionsRow? {
    if (collections.cards.isEmpty()) return null
    val only = collections.cards.singleOrNull()
    if (only != null && collections.preview.isNotEmpty()) {
        return ItemCollectionsRow.Preview(only, collections.preview.take(ITEM_COLLECTION_PREVIEW_LIMIT))
    }
    return ItemCollectionsRow.AlsoIn(collections.cards)
}
