package org.siloserver.silo.model.catalog

import org.siloserver.silo.model.section.SectionCollectionCard

/**
 * The collections holding a movie or series, as its page reads them
 * (`listCatalogItemCollections`): [cards] are server collections first, then
 * personal ones, already filtered by the server's viewer rules. [preview]
 * holds other titles of the collection and is filled only when exactly one
 * collection holds the title.
 */
data class ItemCollections(
    val cards: List<SectionCollectionCard>,
    val preview: List<BrowseItem>,
)
