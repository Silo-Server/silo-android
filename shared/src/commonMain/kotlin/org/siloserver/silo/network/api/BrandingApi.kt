package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.ApiResult

@Serializable
data class BrandingStatus(
    @SerialName("server_name")
    val serverName: String? = null,
)

open class BrandingApi(private val client: HttpClient) {
    open suspend fun getBranding(): ApiResult<BrandingStatus> = safeApiCall {
        client.get("/api/v1/theme/branding") {
            timeout {
                connectTimeoutMillis = BRANDING_TIMEOUT_MS
                requestTimeoutMillis = BRANDING_TIMEOUT_MS
                socketTimeoutMillis = BRANDING_TIMEOUT_MS
            }
        }
    }

    private companion object {
        const val BRANDING_TIMEOUT_MS = 6_000L
    }
}
