package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.section.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.map
import org.siloserver.silo.network.apiv2.*

class SectionApi(private val client: HttpClient, private val v2: CatalogV2Api = CatalogV2Api(client),
    private val sectionItems: LibrarySectionItemsV2Api? = null,
    private val home: HomeSectionsV2Api? = null) {

    // --- Home ---

    suspend fun captureHomeAuthority() = home?.capture()
    suspend fun isHomeAuthorityCurrent(owner: org.siloserver.silo.network.AuthScopeSnapshot) = home?.current(owner) == true
    suspend fun getHomeSections(owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<SectionsResponse> =
        home?.list(owner) ?: ApiResult.Error(0, "unavailable", "The scoped home reader is unavailable.")

    suspend fun getHomeLayout(): ApiResult<HomeLayoutResponse> = safeApiCall {
        client.get("/api/v1/home/layout")
    }

    suspend fun getHomeSections(): ApiResult<SectionsResponse> = safeApiCall {
        client.get("/api/v1/home/sections")
    }

    suspend fun getHomeSectionItems(sectionId: String): ApiResult<HomeSectionItemsResponse> = safeApiCall {
        client.get("/api/v1/home/sections/$sectionId/items")
    }

    // --- Library Sections ---

    suspend fun getLibrarySections(libraryId: Int, owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<SectionsResponse> =
        sectionItems?.list(libraryId, owner) ?: ApiResult.Error(0, "unavailable", "The library section reader is unavailable.")

    suspend fun captureLibrarySectionAuthority() = sectionItems?.capture()
    suspend fun isLibrarySectionAuthorityCurrent(owner: org.siloserver.silo.network.AuthScopeSnapshot) = sectionItems?.current(owner) == true
    suspend fun getLibrarySectionItems(libraryId: Int, sectionId: String, owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<HomeSectionItemsResponse> =
        sectionItems?.read(libraryId, sectionId, owner) ?: ApiResult.Error(0, "unavailable", "The library section reader is unavailable.")

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
