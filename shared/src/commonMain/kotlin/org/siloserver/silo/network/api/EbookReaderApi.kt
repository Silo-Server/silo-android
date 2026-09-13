package org.siloserver.silo.network.api

import org.siloserver.silo.model.ebook.EbookConversionCapability
import org.siloserver.silo.model.ebook.EbookReaderProgress
import org.siloserver.silo.model.ebook.SaveEbookProgressRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.EbookReaderV2Api
import io.ktor.http.encodeURLPathPart

open class EbookReaderApi(private val v2: EbookReaderV2Api) {
    fun readPath(contentId: String, fileId: Int): String =
        "/api/v2/ebooks/${contentId.encodeURLPathPart()}/files/$fileId/read"

    open suspend fun getConversionCapability(): ApiResult<EbookConversionCapability> = v2.capability()

    open suspend fun getProgress(
        contentId: String,
        scope: AuthScopeSnapshot? = null,
    ): ApiResult<EbookReaderProgress> = v2.progress(contentId, scope)

    open suspend fun saveProgress(
        contentId: String,
        request: SaveEbookProgressRequest,
        scope: AuthScopeSnapshot? = null,
    ): ApiResult<EbookReaderProgress> = v2.saveProgress(contentId, request, scope)
}
