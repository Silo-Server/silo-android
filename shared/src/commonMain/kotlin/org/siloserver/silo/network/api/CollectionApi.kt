package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.HttpResponse
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.PageInfo
import org.siloserver.silo.network.apiv2.identityChanged
import org.siloserver.silo.network.apiv2.ownedV2Call
import org.siloserver.silo.network.apiv2.safeApiV2Call
import org.siloserver.silo.network.apiv2.stillOwns
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.authScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogEffectiveSort
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.model.personal.CollectionGroup
import org.siloserver.silo.model.personal.CollectionsResponse
import org.siloserver.silo.model.personal.CreateCollectionGroupRequest
import org.siloserver.silo.model.personal.CreateCollectionRequest
import org.siloserver.silo.model.personal.ReorderCollectionGroupsRequest
import org.siloserver.silo.model.personal.ReorderCollectionsRequest
import org.siloserver.silo.model.personal.UpdateCollectionGroupRequest
import org.siloserver.silo.model.personal.UpdateCollectionRequest
import org.siloserver.silo.network.ApiResult
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A canonical editor read; keep this snapshot unchanged until explicit reload. */
data class CollectionEditor<T>(val value: T, val etag: String, val scope: AuthScopeSnapshot?)

@Serializable
data class CollectionCapabilities(
    val groups: Boolean,
    val imports: Boolean,
    val artwork: Boolean,
    @SerialName("item_reorder") val itemReorder: Boolean,
)

@Serializable
data class CollectionOrder(
    @SerialName("ordered_ids") val orderedIds: List<String>,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

data class CollectionContinuation(
    val cursor: String,
    val collectionId: String,
    val limit: Int,
    val scope: AuthScopeSnapshot?,
    val seen: Set<String> = emptySet(),
    val libraryId: Int? = null,
)
data class CollectionItemsPage(val catalog: CatalogResponse, val continuation: CollectionContinuation?)

@Serializable
internal data class CollectionCatalogResponse(
    val items: List<BrowseItem>,
    val page: PageInfo,
    val total: Int = 0,
    @SerialName("total_exact") val totalExact: Boolean? = null,
    @SerialName("effective_sort") val effectiveSort: CatalogEffectiveSort? = null,
)

class CollectionApi(
    private val client: HttpClient,
    private val gate: ApiV2Gate,
    private val tokenManager: TokenManager? = null,
) {
    private suspend inline fun <reified T> readEditor(path: String): ApiResult<CollectionEditor<T>> {
        val scope = tokenManager?.snapshotCurrentScope()
        var etag: String? = null
        val result = ownedV2Call<T, T>(gate, tokenManager, scope, OwnerPolicy.IDENTITY, null, { owner ->
            client.get(path) { owner?.let { authScope(it) } }.also { etag = it.headers[HttpHeaders.ETag] }
        }) { it }
        return when (result) {
            is ApiResult.Success -> {
                val tag = etag
                if (tag.isNullOrBlank() || tag.startsWith("W/")) ApiResult.Error(0, "missing_etag", "Reload the collection before editing.")
                else ApiResult.Success(CollectionEditor(result.data, tag, scope))
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    private suspend inline fun <reified T> guarded(editor: CollectionEditor<*>, block: () -> HttpResponse): ApiResult<T> {
        if (editor.scope != null && !editor.scope.stillOwns(tokenManager, OwnerPolicy.IDENTITY)) return identityChanged()
        val result = safeApiV2Call<T>(gate, block)
        return if (result is ApiResult.Error && result.code == 412)
            result.copy(message = "This collection changed. Reload before trying again; your changes have been kept.")
        else result
    }

    private fun HttpRequestBuilder.precondition(editor: CollectionEditor<*>) {
        header(HttpHeaders.IfMatch, editor.etag)
        editor.scope?.let { authScope(it) }
    }

    suspend fun capabilities(): ApiResult<CollectionCapabilities> = safeApiV2Call(gate) { client.get("/api/v2/collections/capabilities") }

    suspend fun getCollection(id: String): ApiResult<CollectionEditor<Collection>> = readEditor("/api/v2/collections/$id")
    suspend fun getGroup(id: String): ApiResult<CollectionEditor<CollectionGroup>> = readEditor("/api/v2/collections/groups/$id")
    suspend fun getGroupsOrder(): ApiResult<CollectionEditor<CollectionOrder>> = readEditor("/api/v2/collections/groups/order")
    suspend fun getCollectionsOrder(groupId: String? = null): ApiResult<CollectionEditor<CollectionOrder>> =
        readEditor("/api/v2/collections/order" + (groupId?.let { "?group_id=${it.encodeURLParameter()}" } ?: ""))
    suspend fun getItemsOrder(id: String): ApiResult<CollectionEditor<CollectionOrder>> = readEditor("/api/v2/collections/$id/items/order")


    suspend fun listCollections(): ApiResult<CollectionsResponse> = safeApiV2Call(gate) {
        client.get("/api/v2/collections")
    }

    suspend fun createCollection(request: CreateCollectionRequest): ApiResult<Collection> = safeApiV2Call(gate) {
        client.post("/api/v2/collections") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun updateCollection(
        id: String,
        request: UpdateCollectionRequest,
        editor: CollectionEditor<Collection>,
    ): ApiResult<Collection> = guarded(editor) {
        client.patch("/api/v2/collections/$id") {
            precondition(editor)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /**
     * Moves a collection between groups. Sends `group_id` explicitly as
     * the JSON literal `null` for the Ungrouped target — the shared
     * [org.siloserver.silo.network.SiloJson] is configured with
     * `explicitNulls = false`, which would otherwise drop the field from
     * the body and leave the server unable to distinguish "clear group"
     * from "don't change group".
     */
    suspend fun moveCollectionToGroup(
        id: String,
        groupId: String?,
        editor: CollectionEditor<Collection>,
    ): ApiResult<Collection> = guarded(editor) {
        client.patch("/api/v2/collections/$id") {
            precondition(editor)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("group_id", groupId?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    }

    suspend fun deleteCollection(id: String, editor: CollectionEditor<*>): ApiResult<Unit> = guarded(editor) {
        client.delete("/api/v2/collections/$id") { precondition(editor) }
    }

    suspend fun getCollectionItems(
        id: String,
        continuation: CollectionContinuation? = null,
        limit: Int = 40,
        libraryId: Int? = null,
    ): ApiResult<CollectionItemsPage> {
        val size = limit.coerceIn(1, 100)
        if (
            continuation != null &&
            (continuation.collectionId != id || continuation.limit != size || continuation.libraryId != libraryId)
        )
            return ApiResult.Error(0, "invalid_cursor", "Reload this collection to continue.")
        val scope = continuation?.scope ?: tokenManager?.snapshotCurrentScope()
        val result = ownedV2Call<CollectionCatalogResponse, CollectionCatalogResponse>(gate, tokenManager, scope, OwnerPolicy.IDENTITY, null, { owner ->
            client.get("/api/v2/catalog") {
                owner?.let { authScope(it) }
                parameter("source", "user_collection")
                parameter("collection_id", id)
                libraryId?.let { parameter("library_id", it) }
                parameter("limit", size)
                continuation?.let { parameter("cursor", it.cursor) }
            }
        }) { it }
        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                val cursor = body.page.nextCursor
                if (body.page.hasMore && (cursor.isNullOrBlank() || cursor == continuation?.cursor || cursor in (continuation?.seen ?: emptySet())))
                    ApiResult.Error(0, "invalid_cursor", "Reload this collection; the server returned invalid pagination.")
                else ApiResult.Success(CollectionItemsPage(
                    CatalogResponse(total = body.total, totalExact = body.totalExact, hasMore = body.page.hasMore, items = body.items, effectiveSort = body.effectiveSort),
                    if (body.page.hasMore) CollectionContinuation(
                        cursor = cursor!!,
                        collectionId = id,
                        limit = size,
                        scope = scope,
                        seen = (continuation?.seen ?: emptySet()) + cursor,
                        libraryId = libraryId,
                    ) else null,
                ))
            }
            is ApiResult.Error -> if (result.error == "invalid_cursor") result.copy(message = "This collection changed. Reload it to continue.") else result
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun addItem(
        collectionId: String,
        itemId: String
    ): ApiResult<Unit> = safeApiV2Call(gate) {
        client.put("/api/v2/collections/$collectionId/items/$itemId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("position", JsonPrimitive(0)) })
        }
    }

    suspend fun removeItem(
        collectionId: String,
        itemId: String
    ): ApiResult<Unit> = safeApiV2Call(gate) {
        client.delete("/api/v2/collections/$collectionId/items/$itemId")
    }

    // --- Collection groups ---

    suspend fun createGroup(request: CreateCollectionGroupRequest): ApiResult<CollectionGroup> = safeApiV2Call(gate) {
        client.post("/api/v2/collections/groups") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun updateGroup(
        id: String,
        request: UpdateCollectionGroupRequest,
        editor: CollectionEditor<CollectionGroup>,
    ): ApiResult<CollectionGroup> = guarded(editor) {
        client.patch("/api/v2/collections/groups/$id") {
            precondition(editor)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun deleteGroup(id: String, editor: CollectionEditor<*>): ApiResult<Unit> = guarded(editor) {
        client.delete("/api/v2/collections/groups/$id") { precondition(editor) }
    }

    suspend fun reorderGroups(request: ReorderCollectionGroupsRequest, editor: CollectionEditor<CollectionOrder>): ApiResult<Unit> = guarded(editor) {
        require(!editor.value.hasMore) { "This collection exceeds the editable order window." }
        client.put("/api/v2/collections/groups/order") {
            precondition(editor)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun reorderCollections(request: ReorderCollectionsRequest, editor: CollectionEditor<CollectionOrder>): ApiResult<Unit> = guarded(editor) {
        require(!editor.value.hasMore) { "This collection exceeds the editable order window." }
        client.put("/api/v2/collections/order") {
            precondition(editor)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }
    suspend fun reorderItems(id: String, orderedIds: List<String>, editor: CollectionEditor<CollectionOrder>): ApiResult<Unit> = guarded(editor) {
        require(!editor.value.hasMore) { "This collection exceeds the editable order window." }
        client.put("/api/v2/collections/$id/items/order") {
            precondition(editor)
            contentType(ContentType.Application.Json)
            setBody(ReorderCollectionGroupsRequest(orderedIds))
        }
    }

}
