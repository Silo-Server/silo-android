package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.identityChanged
import org.siloserver.silo.network.apiv2.ownedV2Call
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.requireSiloAuth

interface RatingsCapabilityApi {
    /** The rating sources the server shows (`imdb`, `tmdb`, `rt_critic`, ...); empty when unavailable. */
    suspend fun shownSources(): ApiResult<Set<String>>
}

@Serializable private data class RatingsCapabilitySource(val source: String)

@Serializable private data class RatingsCapability(
    val state: String,
    val sources: List<RatingsCapabilitySource> = emptyList(),
)

/** `GET /api/v2/capabilities/ratings`, read as the current profile. */
class DefaultRatingsCapabilityApi(
    private val client: HttpClient,
    private val tokens: TokenManager? = null,
    private val gate: ApiV2Gate,
) : RatingsCapabilityApi {
    override suspend fun shownSources(): ApiResult<Set<String>> {
        val owner = tokens?.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
            ?: return identityChanged()
        return ownedV2Call<RatingsCapability, Set<String>>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/capabilities/ratings") { authScope(scope!!); requireSiloAuth() }
        }) { capability ->
            if (capability.state == "available") capability.sources.map { it.source }.toSet() else emptySet()
        }
    }
}
