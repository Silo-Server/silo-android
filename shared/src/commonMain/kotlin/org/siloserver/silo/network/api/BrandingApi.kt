package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.skipSiloAuth
import org.siloserver.silo.network.apiv2.safeApiV2Call

@Serializable
data class BrandingStatus(
    @SerialName("server_name")
    val serverName: String? = null,
)

open class BrandingApi(private val client: HttpClient) {
    open suspend fun getBranding(): ApiResult<BrandingStatus> {
        val result = safeApiV2Call<BrandingStatus>(org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted) { request("/api/v2/theme/branding") }
        return if (result is ApiResult.Error && result.code == 404)
            safeApiCall { request("/api/v1/theme/branding") } else result
    }

    private suspend fun request(path: String) = client.get(path) {
            // Public identity probe, like checkHealth() which it replaced as
            // the primary source of a server's display name. Without this it
            // carries a bearer it never needed, so a dead session would fail
            // it and silently fall back to the compatibility name this
            // endpoint exists to stop using.
            skipSiloAuth()
            timeout {
                connectTimeoutMillis = BRANDING_TIMEOUT_MS
                requestTimeoutMillis = BRANDING_TIMEOUT_MS
                socketTimeoutMillis = BRANDING_TIMEOUT_MS
            }
    }

    private companion object {
        const val BRANDING_TIMEOUT_MS = 6_000L
    }
}
