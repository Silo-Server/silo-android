package org.siloserver.silo.repository

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
import org.siloserver.silo.network.api.CollectionContinuation
import org.siloserver.silo.network.api.CollectionItemsPage
import org.siloserver.silo.network.api.CollectionApi
import org.siloserver.silo.network.api.CollectionEditor
import org.siloserver.silo.network.api.CollectionOrder

class CollectionRepository(
    private val collectionApi: CollectionApi,
) {
    suspend fun capabilities() = collectionApi.capabilities()
    suspend fun getCollection(id: String) = collectionApi.getCollection(id)
    suspend fun getGroup(id: String) = collectionApi.getGroup(id)
    suspend fun getGroupsOrder() = collectionApi.getGroupsOrder()
    suspend fun getCollectionsOrder(groupId: String? = null) = collectionApi.getCollectionsOrder(groupId)
    suspend fun getItemsOrder(id: String) = collectionApi.getItemsOrder(id)

    suspend fun reorderItems(id: String, orderedIds: List<String>, editor: CollectionEditor<CollectionOrder>) =
        collectionApi.reorderItems(id, orderedIds, editor)

    /** Lists all collections and their groups for the current user. */
    suspend fun listCollections(): ApiResult<CollectionsResponse> =
        collectionApi.listCollections()

    /** Creates a new collection with the given name and optional type. */
    suspend fun createCollection(
        name: String,
        collectionType: String? = null,
    ): ApiResult<Collection> =
        collectionApi.createCollection(
            CreateCollectionRequest(name = name, collectionType = collectionType),
        )

    /** Updates an existing collection. */
    suspend fun updateCollection(
        id: String,
        request: UpdateCollectionRequest,
        editor: CollectionEditor<Collection>,
    ): ApiResult<Collection> =
        collectionApi.updateCollection(id, request, editor)

    /** Deletes a collection by ID. */
    suspend fun deleteCollection(id: String, editor: CollectionEditor<*>): ApiResult<Unit> =
        collectionApi.deleteCollection(id, editor)

    /** Lists items in a collection with pagination. */
    suspend fun getItems(
        collectionId: String,
        continuation: CollectionContinuation? = null,
        limit: Int = 40,
    ): ApiResult<CollectionItemsPage> =
        collectionApi.getCollectionItems(collectionId, continuation, limit)

    /** Adds an item to a collection. */
    suspend fun addItem(collectionId: String, itemId: String): ApiResult<Unit> =
        collectionApi.addItem(collectionId, itemId)

    /** Removes an item from a collection. */
    suspend fun removeItem(collectionId: String, itemId: String): ApiResult<Unit> =
        collectionApi.removeItem(collectionId, itemId)

    /** Moves a collection into a group (or to Ungrouped when [groupId] is null). */
    suspend fun moveCollectionToGroup(id: String, groupId: String?, editor: CollectionEditor<Collection>): ApiResult<Collection> =
        collectionApi.moveCollectionToGroup(id, groupId, editor)

    // --- Groups ---

    suspend fun createGroup(name: String): ApiResult<CollectionGroup> =
        collectionApi.createGroup(CreateCollectionGroupRequest(name = name))

    suspend fun renameGroup(id: String, name: String, editor: CollectionEditor<CollectionGroup>): ApiResult<CollectionGroup> =
        collectionApi.updateGroup(id, UpdateCollectionGroupRequest(name = name), editor)

    suspend fun deleteGroup(id: String, editor: CollectionEditor<*>): ApiResult<Unit> =
        collectionApi.deleteGroup(id, editor)

    suspend fun reorderGroups(orderedIds: List<String>, editor: CollectionEditor<CollectionOrder>): ApiResult<Unit> =
        collectionApi.reorderGroups(ReorderCollectionGroupsRequest(orderedIds = orderedIds), editor)

    suspend fun reorderCollections(orderedIds: List<String>, editor: CollectionEditor<CollectionOrder>, groupId: String? = null): ApiResult<Unit> =
        collectionApi.reorderCollections(
            ReorderCollectionsRequest(orderedIds = orderedIds, groupId = groupId), editor
        )
}
