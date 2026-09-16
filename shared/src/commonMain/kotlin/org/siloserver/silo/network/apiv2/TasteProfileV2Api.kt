package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.recommendation.TasteProfile
import org.siloserver.silo.network.*

class TasteProfileV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    suspend fun read(owner: AuthScopeSnapshot): ApiResult<TasteProfile> =
        ownedV2Call<TasteProfile, TasteProfile>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/recommendations/taste-profile") { authScope(scope!!); requireSiloAuth() }
        }) { it }
}
