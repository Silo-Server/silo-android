package org.siloserver.silo.network.api

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.recommendation.DiscoverResponse
import org.siloserver.silo.model.recommendation.TasteProfile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.DiscoverV2Api
import org.siloserver.silo.network.apiv2.SimilarCardsV2Api
import org.siloserver.silo.network.apiv2.TasteProfileV2Api
import io.ktor.client.HttpClient

/** Viewer-scoped v2 recommendation reads; DI supplies the real token manager. */
class RecommendationApi(client: HttpClient,
    private val similar: SimilarCardsV2Api = SimilarCardsV2Api(client, TokenManagerImpl(), ApiV2Gate.Unrestricted),
    private val taste: TasteProfileV2Api = TasteProfileV2Api(client, TokenManagerImpl(), ApiV2Gate.Unrestricted),
    private val discover: DiscoverV2Api = DiscoverV2Api(client, TokenManagerImpl(), ApiV2Gate.Unrestricted)) {

    suspend fun captureDiscoverAuthority() = discover.capture()
    suspend fun isDiscoverAuthorityCurrent(owner: AuthScopeSnapshot) = discover.current(owner)
    suspend fun getDiscover(owner: AuthScopeSnapshot): ApiResult<DiscoverResponse> = discover.read(owner)

    suspend fun getTasteProfile(owner: AuthScopeSnapshot): ApiResult<TasteProfile> = taste.read(owner)

    suspend fun captureSimilarAuthority() = similar.capture()
    suspend fun isSimilarAuthorityCurrent(owner: AuthScopeSnapshot) = similar.current(owner)
    suspend fun getSimilar(contentId: String, limit: Int = 12, owner: AuthScopeSnapshot): ApiResult<List<BrowseItem>> =
        similar.list(contentId, limit, owner)
}
