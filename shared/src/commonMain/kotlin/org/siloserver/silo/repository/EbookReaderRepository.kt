package org.siloserver.silo.repository

import org.siloserver.silo.model.ebook.EbookAnnotation
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.EbookReaderV2Api
import org.siloserver.silo.model.ebook.SaveEbookProgressRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.EbookReaderApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EbookReaderRepository(private val api: EbookReaderApi, private val annotations: EbookReaderV2Api) {
    // Session-cached Kindle->EPUB capability. This repo is a DI singleton, so the
    // result is shared across the detail and reader screens and fetched at most
    // once per session. Defaults to false on any error (old server / offline).
    private var cachedKindleConversion: Boolean? = null
    private val capabilityMutex = Mutex()

    fun readPath(contentId: String, fileId: Int): String =
        api.readPath(contentId, fileId)

    suspend fun isKindleConversionAvailable(): Boolean {
        cachedKindleConversion?.let { return it }
        return capabilityMutex.withLock {
            cachedKindleConversion ?: run {
                val enabled = when (val r = api.getConversionCapability()) {
                    is ApiResult.Success -> r.data.enabled
                    else -> false
                }
                cachedKindleConversion = enabled
                enabled
            }
        }
    }

    suspend fun getProgress(contentId: String, scope: org.siloserver.silo.network.AuthScopeSnapshot? = null) =
        api.getProgress(contentId, scope)

    suspend fun saveProgress(contentId: String, request: SaveEbookProgressRequest) =
        api.saveProgress(contentId, request)

    suspend fun listAnnotations(contentId: String, scope: AuthScopeSnapshot) =
        annotations.list(contentId, scope)

    suspend fun createBookmark(contentId: String, id: String, location: String, scope: AuthScopeSnapshot) =
        annotations.createBookmark(contentId, id, location, scope)

    suspend fun deleteAnnotation(contentId: String, annotation: EbookAnnotation, scope: AuthScopeSnapshot) =
        annotations.delete(contentId, annotation, scope)
}
