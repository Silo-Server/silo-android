package org.siloserver.silo.repository

import org.siloserver.silo.model.metadata.MetadataAiStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.MetadataAiApi

/** Thin pass-through over [MetadataAiApi], matching the RequestsRepository shape. */
class MetadataAiRepository(
    private val api: MetadataAiApi,
) {
    suspend fun status(): ApiResult<MetadataAiStatus> = api.status()

    suspend fun translateDescription(contentId: String, targetLanguage: String, scope: org.siloserver.silo.network.AuthScopeSnapshot? = null): ApiResult<org.siloserver.silo.model.metadata.MetadataTranslationJob> =
        api.translateDescription(contentId, targetLanguage, scope)

    suspend fun captureAuthority() = api.captureAuthority()
    suspend fun isCurrent(scope: org.siloserver.silo.network.AuthScopeSnapshot?) = api.isCurrent(scope)
    suspend fun refreshDetail(contentId: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?) = api.refreshDetail(contentId, scope)
}
