package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.skipSiloAuth
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.safeApiV2Call

@Serializable
data class BrandingStatus(
    @SerialName("server_name")
    val serverName: String? = null,
)

open class BrandingApi(private val client: HttpClient) {
    // Public identity probe of a possibly not-yet-connected server; the active entry's verdict must not gate it.
    open suspend fun getBranding(): ApiResult<BrandingStatus> {
        return safeApiV2Call<BrandingStatus>(ApiV2Gate.Unrestricted) { request("/api/v2/theme/branding") }
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
