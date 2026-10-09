package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.shuffle.Shuffle
import org.siloserver.silo.model.shuffle.ShuffleCapability
import org.siloserver.silo.model.shuffle.ShuffleScopeKind
import org.siloserver.silo.network.*

@Serializable
private data class ShuffleScopeRequestV2(val kind: String, val id: String)
@Serializable
private data class CreateShuffleRequestV2(val scope: ShuffleScopeRequestV2)
@Serializable
private data class AdvanceShuffleRequestV2(@SerialName("from_content_id") val fromContentId: String)
@Serializable
private data class SkipShuffleItemRequestV2(@SerialName("next_content_id") val nextContentId: String)

/**
 * `/api/v2/shuffles`: the server picks every item of a shuffle, so the client
 * only starts one, moves it on, replaces its next pick, and stops it.
 * Advance and skip name the item that holds the position, so a retry after a
 * lost response returns the same shuffle unchanged. `404` means the profile
 * cannot see the shuffle or its scope; `409` means nothing in it can play.
 */
class ShufflesV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) {
    suspend fun capability(): ApiResult<ShuffleCapability> =
        exchange(HttpMethod.Get, "/api/v2/shuffles/capabilities", HttpStatusCode.OK)

    suspend fun create(kind: ShuffleScopeKind, id: String): ApiResult<Shuffle> {
        if (id.isBlank()) return ApiResult.Error(422, "validation_failed", "Invalid shuffle scope.")
        return shuffleExchange(HttpMethod.Post, "/api/v2/shuffles", HttpStatusCode.Created) {
            contentType(ContentType.Application.Json)
            setBody(CreateShuffleRequestV2(ShuffleScopeRequestV2(kind.wire, id)))
        }
    }

    suspend fun get(shuffleId: String): ApiResult<Shuffle> {
        if (shuffleId.isBlank()) return invalidShuffle()
        return shuffleExchange(HttpMethod.Get, path(shuffleId), HttpStatusCode.OK)
    }

    /** Moves the shuffle past [fromContentId], the item that finished: `next` becomes `current`. */
    suspend fun advance(shuffleId: String, fromContentId: String): ApiResult<Shuffle> {
        if (shuffleId.isBlank() || fromContentId.isBlank()) return invalidShuffle()
        return shuffleExchange(HttpMethod.Post, "${path(shuffleId)}/advance", HttpStatusCode.OK) {
            contentType(ContentType.Application.Json)
            setBody(AdvanceShuffleRequestV2(fromContentId))
        }
    }

    /** Pick Another: replaces [nextContentId], the announced next item, with another pick. */
    suspend fun skip(shuffleId: String, nextContentId: String): ApiResult<Shuffle> {
        if (shuffleId.isBlank() || nextContentId.isBlank()) return invalidShuffle()
        return shuffleExchange(HttpMethod.Post, "${path(shuffleId)}/skip", HttpStatusCode.OK) {
            contentType(ContentType.Application.Json)
            setBody(SkipShuffleItemRequestV2(nextContentId))
        }
    }

    suspend fun delete(shuffleId: String): ApiResult<Unit> {
        if (shuffleId.isBlank()) return invalidShuffle()
        return exchange(HttpMethod.Delete, path(shuffleId), HttpStatusCode.NoContent)
    }

    private fun path(shuffleId: String) = "/api/v2/shuffles/${shuffleId.encodeURLPathPart()}"

    private fun invalidShuffle(): ApiResult.Error = ApiResult.Error(422, "validation_failed", "Invalid shuffle request.")

    private suspend inline fun shuffleExchange(
        method: HttpMethod,
        path: String,
        expected: HttpStatusCode,
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<Shuffle> = exchange<Shuffle, Shuffle>(method, path, expected, {
        parameter("image_size", "large")
        configure()
    }) { shuffle ->
        require(shuffle.id.isNotBlank() && shuffle.scope.id.isNotBlank()) { "The shuffle response has no identity." }
        require(shuffle.current.contentId.isNotBlank() && shuffle.next.contentId.isNotBlank()) {
            "The shuffle response has no pick."
        }
        shuffle
    }

    private suspend inline fun <reified T> exchange(
        method: HttpMethod,
        path: String,
        expected: HttpStatusCode,
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> = exchange<T, T>(method, path, expected, configure) { it }

    private suspend inline fun <reified T, R> exchange(
        method: HttpMethod,
        path: String,
        expected: HttpStatusCode,
        noinline configure: HttpRequestBuilder.() -> Unit,
        crossinline project: (T) -> R,
    ): ApiResult<R> {
        val owner = tokens.captureProfileScope() ?: return identityChanged()
        return ownedV2Call<T, R>(gate, tokens, owner, OwnerPolicy.PROFILE, expected, { scope ->
            client.request(path) {
                this.method = method
                authScope(scope!!); requireSiloAuth()
                if (method != HttpMethod.Get) singleAttempt()
                configure()
            }
        }) { project(it) }
    }
}
