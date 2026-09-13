package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.siloserver.silo.model.recommendation.TasteProfile
import org.siloserver.silo.network.*

@Serializable private data class TasteSummary(
    @SerialName("top_genres") val genres: List<String>,
    @SerialName("favorite_directors") val directors: List<String>,
    @SerialName("signal_counts") val counts: Map<String, Int>,
    @SerialName("updated_at") val updated: String? = null,
)

class TasteProfileV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
    suspend fun current(owner: AuthScopeSnapshot): Boolean = owner.stillOwns(tokens, OwnerPolicy.FULL)
    suspend fun read(owner: AuthScopeSnapshot): ApiResult<TasteProfile> =
        ownedV2Call<TasteSummary, TasteProfile>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/recommendations/taste-profile") { authScope(scope!!); requireSiloAuth() }
        }) { TasteProfile(it.genres, it.directors, it.counts, it.updated) }
}
