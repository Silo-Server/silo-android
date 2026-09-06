package org.siloserver.silo.network.api

import org.siloserver.silo.model.recommendation.DiscoverResponse
import org.siloserver.silo.model.recommendation.TasteProfile
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter

class RecommendationApi(private val client: HttpClient, private val similar: org.siloserver.silo.network.apiv2.SimilarCardsV2Api? = null,
    private val taste: org.siloserver.silo.network.apiv2.TasteProfileV2Api? = null) {

    suspend fun getDiscover(): ApiResult<DiscoverResponse> = safeApiCall {
        client.get("/api/v1/recommendations/discover")
    }

    suspend fun captureTasteAuthority() = taste?.capture()
    suspend fun isTasteAuthorityCurrent(owner: org.siloserver.silo.network.AuthScopeSnapshot) = taste?.current(owner) == true
    suspend fun getTasteProfile(owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<TasteProfile> =
        taste?.read(owner) ?: ApiResult.Error(0,"unavailable","The taste-summary transport is unavailable.")

    suspend fun captureSimilarAuthority() = similar?.capture()
    suspend fun isSimilarAuthorityCurrent(owner: org.siloserver.silo.network.AuthScopeSnapshot) = similar?.current(owner) == true
    suspend fun getSimilar(contentId: String, limit: Int = 12, owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<List<org.siloserver.silo.model.catalog.BrowseItem>> =
        similar?.list(contentId, limit, owner) ?: ApiResult.Error(0, "unavailable", "The similar-card transport is unavailable.")
}
