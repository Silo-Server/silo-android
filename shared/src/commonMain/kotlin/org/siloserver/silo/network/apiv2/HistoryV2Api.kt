package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import org.siloserver.silo.network.*

/** One server history page per request; empty pages can still have continuation. */
class HistoryV2Api(
    private val client: HttpClient,
    private val gate: ApiV2Gate,
    private val tokenManager: TokenManager? = null,
) {
    suspend fun page(limit: Int = 40, imageSize: String? = null,
        continuation: HistoryContinuationV2? = null): ApiResult<HistoryPageV2> {
        if (limit !in 1..200) return ApiResult.Error(0, "validation_failed", "History page size must be between 1 and 200.")
        if (continuation != null && (continuation.limit != limit || continuation.imageSize != imageSize))
            return invalidCursor("The history request changed. Reload history.")
        val scope = continuation?.scope ?: tokenManager?.snapshotCurrentScope()
        val result = ownedV2Call<HistoryPageWireV2, HistoryPageWireV2>(gate, tokenManager, scope, OwnerPolicy.IDENTITY, null, { owner ->
            client.get("/api/v2/history") {
                owner?.let { authScope(it) }
                parameter("limit", limit)
                imageSize?.let { parameter("image_size", it) }
                continuation?.let { parameter("cursor", it.cursor) }
            }
        }) { it }
        return when (result) {
            is ApiResult.Success -> {
                val page = result.data.page
                val next = page.nextCursor
                if (page.hasMore && (next.isNullOrBlank() || next in continuation?.seenCursors.orEmpty()) ||
                    !page.hasMore && !next.isNullOrBlank()) {
                    return invalidCursor("The server returned invalid history pagination. Reload history.")
                }
                val seen = continuation?.seenContentIds.orEmpty().toMutableSet()
                val items = result.data.items.filter { seen.add(it.item.contentId) }
                val following = if (page.hasMore) HistoryContinuationV2(next!!, scope, limit, imageSize,
                    continuation?.seenCursors.orEmpty() + next, seen) else null
                ApiResult.Success(HistoryPageV2(items, following))
            }
            is ApiResult.Error -> if (result.error == "invalid_cursor")
                result.copy(message = "History changed or expired. Reload history.") else result
            is ApiResult.NetworkError -> result
        }
    }

    private fun invalidCursor(message: String) = ApiResult.Error(0, "invalid_cursor", message)
}
