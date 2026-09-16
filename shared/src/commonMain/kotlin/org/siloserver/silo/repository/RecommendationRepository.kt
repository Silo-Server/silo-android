package org.siloserver.silo.repository

import org.siloserver.silo.model.recommendation.DiscoverResponse
import org.siloserver.silo.model.recommendation.TasteProfile
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.siloserver.silo.network.api.RecommendationApi

class RecommendationRepository(
    private val recommendationApi: RecommendationApi,
) {
    suspend fun getDiscover(owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<DiscoverResponse> =
        recommendationApi.getDiscover(owner)

    suspend fun captureDiscoverAuthority() = recommendationApi.captureDiscoverAuthority()
    suspend fun isDiscoverAuthorityCurrent(owner: org.siloserver.silo.network.AuthScopeSnapshot) = recommendationApi.isDiscoverAuthorityCurrent(owner)

    suspend fun getTasteProfile(owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<TasteProfile> =
        recommendationApi.getTasteProfile(owner)

    suspend fun captureSimilarAuthority() = recommendationApi.captureSimilarAuthority()
    suspend fun isSimilarAuthorityCurrent(owner: org.siloserver.silo.network.AuthScopeSnapshot) = recommendationApi.isSimilarAuthorityCurrent(owner)
    /** The synchronous run fence is evaluated after each suspended authority lookup. */
    suspend fun loadSimilarCards(contentId: String, owner: org.siloserver.silo.network.AuthScopeSnapshot,
        stillCurrent: () -> Boolean, publish: (List<org.siloserver.silo.model.catalog.BrowseItem>) -> Unit) {
        if (!currentCoroutineContext().isActive || !stillCurrent()) return
        // getSimilar guards the owner before and after the exchange.
        val result = recommendationApi.getSimilar(contentId, 12, owner)
        if (!isSimilarAuthorityCurrent(owner) || !currentCoroutineContext().isActive || !stillCurrent()) return
        publish((result as? ApiResult.Success)?.data.orEmpty())
    }

}
