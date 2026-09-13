package org.siloserver.silo.network.api

import io.ktor.client.*
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.section.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.map
import org.siloserver.silo.network.apiv2.*

/** Home and library section reads are viewer-scoped v2 transports; DI supplies the real token manager. */
class SectionApi(client: HttpClient, private val v2: CatalogV2Api = CatalogV2Api(client, ApiV2Gate.Unrestricted),
    private val sectionItems: LibrarySectionItemsV2Api = LibrarySectionItemsV2Api(client, TokenManagerImpl(), ApiV2Gate.Unrestricted),
    private val home: HomeSectionsV2Api = HomeSectionsV2Api(client, TokenManagerImpl(), ApiV2Gate.Unrestricted)) {

    // --- Home ---

    suspend fun captureHomeAuthority() = home.capture()
    suspend fun isHomeAuthorityCurrent(owner: AuthScopeSnapshot) = home.current(owner)
    suspend fun getHomeSections(owner: AuthScopeSnapshot): ApiResult<SectionsResponse> = home.list(owner)

    suspend fun getHomeSectionItems(id: String, owner: AuthScopeSnapshot): ApiResult<HomeSectionItemsResponse> =
        home.section(id, owner)

    suspend fun dismissHomeItem(surface: String, itemId: String, anchor: String, owner: AuthScopeSnapshot): ApiResult<Unit> =
        home.dismiss(surface, itemId, anchor, owner)

    // --- Library Sections ---

    suspend fun getLibrarySections(libraryId: Int, owner: AuthScopeSnapshot): ApiResult<SectionsResponse> =
        sectionItems.list(libraryId, owner)

    suspend fun captureLibrarySectionAuthority() = sectionItems.capture()
    suspend fun isLibrarySectionAuthorityCurrent(owner: AuthScopeSnapshot) = sectionItems.current(owner)
    suspend fun getLibrarySectionItems(libraryId: Int, sectionId: String, owner: AuthScopeSnapshot): ApiResult<HomeSectionItemsResponse> =
        sectionItems.read(libraryId, sectionId, owner)

    // --- Library Collections ---

    suspend fun getLibraryCollections(libraryId: Int): ApiResult<LibraryCollectionsResponse> =
        v2.libraryCollections(libraryId.toString()).map { tab ->
            val definitions = tab.collections.associateBy { it.id }
            fun LibraryCollectionCardV2.card(kind: String) = LibraryCollection(
                id, title, definitions[id]?.collectionType, itemCount, posterUrl, posterThumbhash,
                if (kind == "admin") "regular" else kind, creatorProfileId,
            )
            val groups = tab.groups.map { group ->
                LibraryCollectionGroup(group.id, group.name, if (group.kind == "admin") "regular" else group.kind,
                    group.sortMode, group.sortOrder, group.collections.map { it.card(group.kind) })
            }
            val ungrouped = tab.ungrouped?.let { group ->
                LibraryUngroupedSection(group.sortOrder, group.collections.map { it.card("regular") })
            }
            val flat = if (groups.isNotEmpty() || ungrouped != null) {
                (groups.flatMap { it.collections } + ungrouped?.collections.orEmpty()).distinctBy { it.id }
            } else tab.collections.map {
                LibraryCollection(it.id, it.title, it.collectionType, it.itemCount, it.posterUrl, it.posterThumbhash, "regular")
            }
            LibraryCollectionsResponse(flat, groups, ungrouped)
        }

    /** Saved source order is retained unless the caller explicitly chooses a sort. */
    suspend fun getLibraryCollectionItems(
        collectionId: String,
        continuation: CatalogContinuationV2? = null,
        limit: Int = 60,
        sort: String? = null,
        order: String? = null,
        queryGroups: List<CatalogQueryGroup> = emptyList(),
        match: String? = null,
    ): ApiResult<CatalogResponse> = v2.browse(CatalogQueryV2(
        source = "library_collection", collectionId = collectionId, limit = limit,
        sort = sort, order = order.takeIf { sort != null }, groups = queryGroups.toV2Groups(), match = match,
    ), continuation).map { it.toCatalogResponse() }
}
