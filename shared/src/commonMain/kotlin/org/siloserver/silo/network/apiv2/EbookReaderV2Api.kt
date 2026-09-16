package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.ebook.*
import org.siloserver.silo.network.*

@Serializable
private data class EbookProgressEnvelopeV2(val progress: EbookProgressV2? = null)
@Serializable
private data class EbookProgressV2(
    @SerialName("content_id") val contentId: String,
    @Serializable(with = DetailStringIdSerializer::class)
    @SerialName("file_id") val fileId: String,
    val location: String,
    val progress: Double,
    @SerialName("updated_at") val updatedAt: String,
)
@Serializable
private data class EbookProgressWriteV2(
    @SerialName("file_id") val fileId: String, val location: String, val progress: Double,
    @SerialName("updated_at") val updatedAt: String,
)
@Serializable
private data class EbookCapabilityV2(@SerialName("kindle_conversion") val kindleConversion: Boolean,
    @SerialName("source_formats") val sourceFormats: List<String>, @SerialName("served_format") val servedFormat: String,
    val header: String, @SerialName("header_failed_value") val headerFailedValue: String)

@Serializable
private data class EbookConfigV2(@SerialName("content_id") val contentId: String, val config: JsonObject,
    @SerialName("updated_at") val updatedAt: String? = null)

data class GuardedEbookConfig(val value: EbookReaderConfig, val etag: String)

@Serializable
private data class AnnotationPage(val items: List<EbookAnnotation>, val page: PageInfo)

class EbookReaderV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    private suspend inline fun <reified T, R> exchange(scope: AuthScopeSnapshot?, method: HttpMethod, path: String,
        expected: HttpStatusCode? = HttpStatusCode.OK, noinline configure: HttpRequestBuilder.() -> Unit = {},
        crossinline project: (T) -> R): ApiResult<R> {
        val captured = scope ?: tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<T, R>(gate, tokens, captured, OwnerPolicy.IDENTITY, expected, { owner ->
            client.request(path) {
                this.method = method; authScope(owner!!); requireSiloAuth()
                if (method != HttpMethod.Get) singleAttempt()
                contentType(ContentType.Application.Json); configure()
            }
        }, project)
    }
    private fun invalid() = ApiResult.Error(0, "invalid_annotations", "The server returned incomplete annotation state.")
    private fun annotationsPath(contentId: String) = "/api/v2/ebooks/${contentId.encodeURLPathPart()}/annotations"
    private fun valid(row: EbookAnnotation, contentId: String) =
        row.contentId == contentId && row.id.isNotBlank() && !row.etag.isNullOrBlank()
    suspend fun capability(): ApiResult<EbookConversionCapability> =
        exchange<EbookCapabilityV2, EbookConversionCapability>(null, HttpMethod.Get, "/api/v2/capabilities/ebooks") {
            EbookConversionCapability(it.kindleConversion, it.sourceFormats, it.servedFormat, it.header, it.headerFailedValue)
        }

    suspend fun progress(contentId: String, scope: AuthScopeSnapshot?): ApiResult<EbookReaderProgress> =
        exchange<EbookProgressEnvelopeV2, EbookReaderProgress>(scope, HttpMethod.Get, "/api/v2/ebooks/${contentId.encodeURLPathPart()}/progress") {
            project(contentId, it.progress, allowAbsent = true)
        }

    suspend fun saveProgress(contentId: String, request: SaveEbookProgressRequest, scope: AuthScopeSnapshot?): ApiResult<EbookReaderProgress> {
        val time = request.updatedAt ?: return ApiResult.Error(0, "event_time_required", "Reading progress needs its original event time.")
        return exchange<EbookProgressEnvelopeV2, EbookReaderProgress>(scope, HttpMethod.Put, "/api/v2/ebooks/${contentId.encodeURLPathPart()}/progress",
            configure = { setBody(EbookProgressWriteV2(request.fileId.toString(), request.location, request.progress, time)) }) {
            project(contentId, it.progress, allowAbsent = false)
        }
    }
    private fun project(contentId: String, value: EbookProgressV2?, allowAbsent: Boolean): EbookReaderProgress {
        if (value == null) {
            require(allowAbsent) { "The server did not confirm saved reading progress." }
            return EbookReaderProgress(contentId = contentId)
        }
        require(value.contentId == contentId && value.progress.isFinite() && value.progress in 0.0..1.0) { "The server returned unsupported reading progress." }
        return EbookReaderProgress(value.contentId, checkedPositiveId(value.fileId), value.location, value.progress, value.updatedAt)
    }

    suspend fun config(contentId: String, scope: AuthScopeSnapshot, request: SaveEbookReaderConfigRequest? = null,
        etag: String? = null): ApiResult<GuardedEbookConfig> {
        if (request != null && etag.isNullOrBlank()) return ApiResult.Error(428, "etag_required", "Reload reader settings before saving.")
        var validator: String? = null
        // Keep the validator together with the response read in this exchange.
        return ownedV2Call<EbookConfigV2, GuardedEbookConfig>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.request("/api/v2/ebooks/${contentId.encodeURLPathPart()}/reader-config") {
                method = if (request == null) HttpMethod.Get else HttpMethod.Put
                authScope(owner!!); requireSiloAuth()
                if (request != null) { singleAttempt(); header(HttpHeaders.IfMatch, etag); setBody(request) }
                contentType(ContentType.Application.Json)
            }.also { validator = it.headers[HttpHeaders.ETag] }
        }) { body ->
            val tag = validator
            require(!tag.isNullOrBlank() && body.contentId == contentId) { "Reader settings returned no matching validator." }
            GuardedEbookConfig(EbookReaderConfig(body.contentId, body.config, body.updatedAt), tag)
        }
    }

    // Annotation writes use caller-retained identities and validators, never implicit retries.
    suspend fun list(contentId: String, scope: AuthScopeSnapshot): ApiResult<EbookAnnotationListResponse> {
        val rows = linkedMapOf<String, EbookAnnotation>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            val result = exchange<AnnotationPage, AnnotationPage>(scope, HttpMethod.Get, annotationsPath(contentId),
                configure = { parameter("limit", 50); cursor?.let { parameter("cursor", it) } }) { it }
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
        projectAnnotation(contentId, id, exchange<EbookAnnotation, EbookAnnotation>(scope, HttpMethod.Post, annotationsPath(contentId), expected = null, // 201 created, or 200 for the retried identity
            configure = { setBody(buildJsonObject { put("id", id); put("kind", "bookmark"); put("location", location) }) }) { it })

    suspend fun patch(contentId: String, annotation: EbookAnnotation, patch: JsonObject,
        scope: AuthScopeSnapshot): ApiResult<EbookAnnotation> {
        if (!valid(annotation, contentId)) return invalid()
        return projectAnnotation(contentId, annotation.id, exchange<EbookAnnotation, EbookAnnotation>(scope, HttpMethod.Patch,
            "${annotationsPath(contentId)}/${annotation.id.encodeURLPathPart()}",
            configure = { header(HttpHeaders.IfMatch, annotation.etag); setBody(patch) }) { it })
    }

    suspend fun delete(contentId: String, annotation: EbookAnnotation, scope: AuthScopeSnapshot): ApiResult<Unit> {
        if (!valid(annotation, contentId)) return invalid()
        return exchange<Unit, Unit>(scope, HttpMethod.Delete, "${annotationsPath(contentId)}/${annotation.id.encodeURLPathPart()}", HttpStatusCode.NoContent,
            configure = { header(HttpHeaders.IfMatch, annotation.etag) }) { }
    }

    private fun projectAnnotation(contentId: String, id: String, result: ApiResult<EbookAnnotation>): ApiResult<EbookAnnotation> =
        if (result is ApiResult.Success && !(valid(result.data, contentId) && result.data.id == id)) invalid() else result
}
