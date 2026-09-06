package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.ebook.EbookAnnotation
import org.siloserver.silo.model.ebook.EbookAnnotationListResponse
import org.siloserver.silo.network.*

@Serializable
private data class AnnotationPage(val items: List<EbookAnnotation>, val page: PageInfo)

/** Annotation writes use caller-retained identities and validators, never implicit retries. */
class EbookAnnotationsV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    private fun changed() = ApiResult.Error(0, "identity_changed", "The reader's account or profile changed.")
    private fun invalid() = ApiResult.Error(0, "invalid_annotations", "The server returned incomplete annotation state.")
    private fun path(contentId: String) = "/api/v2/ebooks/${contentId.encodeURLPathPart()}/annotations"
    private fun valid(row: EbookAnnotation, contentId: String) =
        row.contentId == contentId && row.id.isNotBlank() && !row.etag.isNullOrBlank()

    private suspend inline fun <reified T> exchange(scope: AuthScopeSnapshot, method: HttpMethod, path: String,
        allowed: Set<Int>, noinline body: HttpRequestBuilder.() -> Unit = {}): ApiResult<T> {
        if (!scope.isSameIdentityAs(tokens.snapshotCurrentScope())) return changed()
        val result = safeApiV2Call<T>(gate) {
            client.request(path) {
                this.method = method; authScope(scope); requireSiloAuth()
                if (method != HttpMethod.Get) singleAttempt()
                contentType(ContentType.Application.Json); body()
            }.also { check(!it.status.isSuccess() || it.status.value in allowed) }
        }
        return if (scope.isSameIdentityAs(tokens.snapshotCurrentScope())) result else changed()
    }

    suspend fun list(contentId: String, scope: AuthScopeSnapshot): ApiResult<EbookAnnotationListResponse> {
        val rows = linkedMapOf<String, EbookAnnotation>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            val result = exchange<AnnotationPage>(scope, HttpMethod.Get, path(contentId), setOf(200)) {
                parameter("limit", 50); cursor?.let { parameter("cursor", it) }
            }
            when (result) {
                is ApiResult.Success -> {
                    val page = result.data
                    if (page.items.size > 50 || page.items.any { !valid(it, contentId) }) return invalid()
                    page.items.forEach { rows[it.id] = it }
                    if (!page.page.hasMore) {
                        if (!page.page.nextCursor.isNullOrBlank()) return invalid()
                        return ApiResult.Success(EbookAnnotationListResponse(rows.values.toList()))
                    }
                    val next = page.page.nextCursor
                    if (next.isNullOrBlank() || !seen.add(next) || page.items.isEmpty()) return invalid()
                    cursor = next
                }
                is ApiResult.Error -> return result
                is ApiResult.NetworkError -> return result
            }
        }
        return invalid() // Do not publish a truncated bookmark list.
    }

    suspend fun createBookmark(contentId: String, id: String, location: String, scope: AuthScopeSnapshot): ApiResult<EbookAnnotation> =
        project(contentId, id, exchange(scope, HttpMethod.Post, path(contentId), setOf(200, 201)) {
            setBody(buildJsonObject { put("id", id); put("kind", "bookmark"); put("location", location) })
        })

    suspend fun patch(contentId: String, annotation: EbookAnnotation, patch: JsonObject,
        scope: AuthScopeSnapshot): ApiResult<EbookAnnotation> {
        if (!valid(annotation, contentId)) return invalid()
        return project(contentId, annotation.id, exchange(scope, HttpMethod.Patch,
            "${path(contentId)}/${annotation.id.encodeURLPathPart()}", setOf(200)) {
            header(HttpHeaders.IfMatch, annotation.etag); setBody(patch)
        })
    }

    suspend fun delete(contentId: String, annotation: EbookAnnotation, scope: AuthScopeSnapshot): ApiResult<Unit> {
        if (!valid(annotation, contentId)) return invalid()
        return exchange(scope, HttpMethod.Delete, "${path(contentId)}/${annotation.id.encodeURLPathPart()}", setOf(204)) {
            header(HttpHeaders.IfMatch, annotation.etag)
        }
    }

    private fun project(contentId: String, id: String, result: ApiResult<EbookAnnotation>): ApiResult<EbookAnnotation> = when (result) {
        is ApiResult.Success -> if (valid(result.data, contentId) && result.data.id == id) result else invalid()
        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }
}
