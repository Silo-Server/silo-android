package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.requireSiloAuth
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.ownedV2Call

@Serializable
data class ImageCapabilities(
    @SerialName("storage_backend") val storageBackend: String? = null,
    val delivery: String? = null,
)

class ImageCapabilitiesApi(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) {
    suspend fun get(scope: AuthScopeSnapshot): ApiResult<ImageCapabilities> =
        ownedV2Call<ImageCapabilities, ImageCapabilities>(gate, tokens, scope, OwnerPolicy.FULL, null, {
            client.get("/api/v2/images/capabilities") { authScope(scope); requireSiloAuth() }
        }) { it }
}
