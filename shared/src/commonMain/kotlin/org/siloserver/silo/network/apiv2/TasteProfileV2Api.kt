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

class TasteProfileV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
    suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now=capture()
        return owner.isSameIdentityAs(now) && owner.serverUrl==now?.serverUrl && owner.profileId==now?.profileId &&
            owner.profileToken==now?.profileToken && owner.credentialGenerationId==now?.credentialGenerationId
    }
    suspend fun read(owner: AuthScopeSnapshot): ApiResult<TasteProfile> {
        if(!current(owner)) return changed()
        val result=safeApiV2Call<TasteSummary>(gate) {
            client.get("/api/v2/recommendations/taste-profile") { authScope(owner); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status==HttpStatusCode.OK) }
        }
        if(!current(owner)) return changed()
        return result.map { TasteProfile(it.genres,it.directors,it.counts,it.updated) }
    }
    private fun changed()=ApiResult.Error(0,"taste_authority_changed","The initiating taste-profile identity changed.")
}
