package org.siloserver.silo.network.api

import org.siloserver.silo.model.ebook.EbookAnnotation
import org.siloserver.silo.model.ebook.EbookAnnotationListResponse
import org.siloserver.silo.model.ebook.EbookConversionCapability
import org.siloserver.silo.model.ebook.EbookReaderProgress
import org.siloserver.silo.model.ebook.SaveEbookAnnotationRequest
import org.siloserver.silo.model.ebook.SaveEbookProgressRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.authScope
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

open class EbookReaderApi(private val client: HttpClient, private val v2: org.siloserver.silo.network.apiv2.EbookReaderV2Api? = null) {
    fun readPath(contentId: String, fileId: Int): String =
        "/api/v2/ebooks/${contentId.encodeURLPathPart()}/files/$fileId/read"

    open suspend fun getConversionCapability(): ApiResult<EbookConversionCapability> = v2?.capability() ?: safeApiCall {
        client.get("/api/v1/ebooks/capability")
    }

    open suspend fun getProgress(
        contentId: String,
        scope: AuthScopeSnapshot? = null,
    ): ApiResult<EbookReaderProgress> = v2?.progress(contentId, scope) ?: safeApiCall {
        client.get("/api/v1/ebooks/${contentId.encodeURLPathPart()}/progress") {
            scope?.let { authScope(it) }
        }
    }

    open suspend fun saveProgress(
        contentId: String,
        request: SaveEbookProgressRequest,
        scope: AuthScopeSnapshot? = null,
    ): ApiResult<EbookReaderProgress> = v2?.saveProgress(contentId, request, scope) ?: safeApiCall {
        client.put("/api/v1/ebooks/${contentId.encodeURLPathPart()}/progress") {
            scope?.let { authScope(it) }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun listAnnotations(contentId: String): ApiResult<EbookAnnotationListResponse> = safeApiCall {
        client.get("/api/v1/ebooks/${contentId.encodeURLPathPart()}/annotations")
    }

    suspend fun createAnnotation(
        contentId: String,
        request: SaveEbookAnnotationRequest,
    ): ApiResult<EbookAnnotation> = safeApiCall {
        client.post("/api/v1/ebooks/${contentId.encodeURLPathPart()}/annotations") {
            setBody(request)
        }
    }

    suspend fun updateAnnotation(
        contentId: String,
        annotationId: String,
        request: SaveEbookAnnotationRequest,
    ): ApiResult<EbookAnnotation> = safeApiCall {
        client.patch("/api/v1/ebooks/${contentId.encodeURLPathPart()}/annotations/${annotationId.encodeURLPathPart()}") {
            setBody(request)
        }
    }

    suspend fun deleteAnnotation(contentId: String, annotationId: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/ebooks/${contentId.encodeURLPathPart()}/annotations/${annotationId.encodeURLPathPart()}")
    }
}
