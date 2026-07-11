package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
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

class CollectionApi(private val client: HttpClient) {

    suspend fun listCollections(): ApiResult<CollectionsResponse> = safeApiCall {
        client.get("/api/v1/collections")
    }

    suspend fun createCollection(request: CreateCollectionRequest): ApiResult<Collection> = safeApiCall {
        client.post("/api/v1/collections") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun updateCollection(
        id: String,
        request: UpdateCollectionRequest
    ): ApiResult<Collection> = safeApiCall {
        client.put("/api/v1/collections/$id") {
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
    ): ApiResult<Collection> = safeApiCall {
        client.put("/api/v1/collections/$id") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("group_id", groupId?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    }

    suspend fun deleteCollection(id: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/collections/$id")
    }

    suspend fun getCollectionItems(
        id: String,
        offset: Int = 0,
        limit: Int = 40
    ): ApiResult<CatalogResponse> = safeApiCall {
        // The legacy membership route is a complete-export compatibility
        // endpoint and returns unhydrated join records. Display clients use
        // the bounded catalog resolver so offset/limit are enforced in SQL.
        client.get("/api/v1/catalog") {
            parameter("source", "user_collection")
            parameter("collection_id", id)
            parameter("offset", offset)
            parameter("limit", limit)
            parameter("include_total", false)
        }
    }

    suspend fun addItem(
        collectionId: String,
        itemId: String
    ): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/collections/$collectionId/items/$itemId")
    }

    suspend fun removeItem(
        collectionId: String,
        itemId: String
    ): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/collections/$collectionId/items/$itemId")
    }

    // --- Collection groups ---

    suspend fun createGroup(request: CreateCollectionGroupRequest): ApiResult<CollectionGroup> = safeApiCall {
        client.post("/api/v1/collections/groups") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun updateGroup(
        id: String,
        request: UpdateCollectionGroupRequest,
    ): ApiResult<CollectionGroup> = safeApiCall {
        client.put("/api/v1/collections/groups/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun deleteGroup(id: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/collections/groups/$id")
    }

    suspend fun reorderGroups(request: ReorderCollectionGroupsRequest): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/collections/groups/order") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun reorderCollections(request: ReorderCollectionsRequest): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/collections/order") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }
}
