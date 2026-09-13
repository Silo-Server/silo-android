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

class EbookReaderV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    private suspend inline fun <reified T> exchange(scope: AuthScopeSnapshot?, method: HttpMethod, path: String,
        allowed: Set<Int> = setOf(200), noinline configure: HttpRequestBuilder.() -> Unit = {}): ApiResult<T> {
        val captured = scope ?: tokens.snapshotCurrentScope() ?: return changed()
        if (!captured.isSameIdentityAs(tokens.snapshotCurrentScope())) return changed()
        val result = safeApiV2Call<T>(gate) {
            client.request(path) {
                this.method = method; authScope(captured); requireSiloAuth()
                if (method != HttpMethod.Get) singleAttempt()
                contentType(ContentType.Application.Json); configure()
            }.also { check(!it.status.isSuccess() || it.status.value in allowed) }
        }
        return if (captured.isSameIdentityAs(tokens.snapshotCurrentScope())) result else changed()
    }
    private fun changed() = ApiResult.Error(0, "identity_changed", "The reader's account or profile changed.")
    private fun invalid() = ApiResult.Error(0, "invalid_annotations", "The server returned incomplete annotation state.")
    private fun annotationsPath(contentId: String) = "/api/v2/ebooks/${contentId.encodeURLPathPart()}/annotations"
    private fun valid(row: EbookAnnotation, contentId: String) =
        row.contentId == contentId && row.id.isNotBlank() && !row.etag.isNullOrBlank()
    suspend fun capability(): ApiResult<EbookConversionCapability> =
        exchange<EbookCapabilityV2>(null, HttpMethod.Get, "/api/v2/capabilities/ebooks").map { EbookConversionCapability(it.kindleConversion, it.sourceFormats, it.servedFormat, it.header, it.headerFailedValue) }

    suspend fun progress(contentId: String, scope: AuthScopeSnapshot?): ApiResult<EbookReaderProgress> =
        project(contentId, exchange(scope, HttpMethod.Get, "/api/v2/ebooks/${contentId.encodeURLPathPart()}/progress"))

    suspend fun saveProgress(contentId: String, request: SaveEbookProgressRequest, scope: AuthScopeSnapshot?): ApiResult<EbookReaderProgress> {
        val time = request.updatedAt ?: return ApiResult.Error(0, "event_time_required", "Reading progress needs its original event time.")
        return project(contentId, exchange(scope, HttpMethod.Put, "/api/v2/ebooks/${contentId.encodeURLPathPart()}/progress") {
            setBody(EbookProgressWriteV2(request.fileId.toString(), request.location, request.progress, time))
        }, allowAbsent = false)
    }
    private fun project(contentId: String, result: ApiResult<EbookProgressEnvelopeV2>, allowAbsent: Boolean = true): ApiResult<EbookReaderProgress> = when (result) {
        is ApiResult.Success -> {
            val value = result.data.progress
            val file = value?.fileId?.toIntOrNull()
            if (value == null) {
                if (allowAbsent) ApiResult.Success(EbookReaderProgress(contentId = contentId))
                else ApiResult.Error(0, "invalid_progress", "The server did not confirm saved reading progress.")
            }
            else if (value.contentId != contentId || file == null || file <= 0 || file.toString() != value.fileId ||
                !value.progress.isFinite() || value.progress !in 0.0..1.0)
                ApiResult.Error(0, "invalid_progress", "The server returned unsupported reading progress.")
            else ApiResult.Success(EbookReaderProgress(value.contentId, file, value.location, value.progress, value.updatedAt))
        }
        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }

    suspend fun config(contentId: String, scope: AuthScopeSnapshot, request: SaveEbookReaderConfigRequest? = null,
        etag: String? = null): ApiResult<GuardedEbookConfig> {
        if (request != null && etag.isNullOrBlank()) return ApiResult.Error(428, "etag_required", "Reload reader settings before saving.")
        var validator: String? = null
        // Keep the validator together with the response read in this exchange.
        if (!scope.isSameIdentityAs(tokens.snapshotCurrentScope())) return changed()
        val result = safeApiV2Call<EbookConfigV2>(gate) {
            client.request("/api/v2/ebooks/${contentId.encodeURLPathPart()}/reader-config") {
                method = if (request == null) HttpMethod.Get else HttpMethod.Put
                authScope(scope); requireSiloAuth()
                if (request != null) { singleAttempt(); header(HttpHeaders.IfMatch, etag); setBody(request) }
                contentType(ContentType.Application.Json)
            }.also { validator = it.headers[HttpHeaders.ETag]; check(!it.status.isSuccess() || it.status.value == 200) }
        }
        if (!scope.isSameIdentityAs(tokens.snapshotCurrentScope())) return changed()
        return when (result) {
            is ApiResult.Success -> if (validator.isNullOrBlank() || result.data.contentId != contentId)
                ApiResult.Error(0, "invalid_config", "Reader settings returned no matching validator.")
                else ApiResult.Success(GuardedEbookConfig(EbookReaderConfig(result.data.contentId, result.data.config, result.data.updatedAt), requireNotNull(validator)))
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    // Annotation writes use caller-retained identities and validators, never implicit retries.
    suspend fun list(contentId: String, scope: AuthScopeSnapshot): ApiResult<EbookAnnotationListResponse> {
        val rows = linkedMapOf<String, EbookAnnotation>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            val result = exchange<AnnotationPage>(scope, HttpMethod.Get, annotationsPath(contentId)) {
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
        projectAnnotation(contentId, id, exchange(scope, HttpMethod.Post, annotationsPath(contentId), setOf(200, 201)) {
            setBody(buildJsonObject { put("id", id); put("kind", "bookmark"); put("location", location) })
        })

    suspend fun patch(contentId: String, annotation: EbookAnnotation, patch: JsonObject,
        scope: AuthScopeSnapshot): ApiResult<EbookAnnotation> {
        if (!valid(annotation, contentId)) return invalid()
        return projectAnnotation(contentId, annotation.id, exchange(scope, HttpMethod.Patch,
            "${annotationsPath(contentId)}/${annotation.id.encodeURLPathPart()}") {
            header(HttpHeaders.IfMatch, annotation.etag); setBody(patch)
        })
    }

    suspend fun delete(contentId: String, annotation: EbookAnnotation, scope: AuthScopeSnapshot): ApiResult<Unit> {
        if (!valid(annotation, contentId)) return invalid()
        return exchange(scope, HttpMethod.Delete, "${annotationsPath(contentId)}/${annotation.id.encodeURLPathPart()}", setOf(204)) {
            header(HttpHeaders.IfMatch, annotation.etag)
        }
    }

    private fun projectAnnotation(contentId: String, id: String, result: ApiResult<EbookAnnotation>): ApiResult<EbookAnnotation> = when (result) {
        is ApiResult.Success -> if (valid(result.data, contentId) && result.data.id == id) result else invalid()
        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }
}
