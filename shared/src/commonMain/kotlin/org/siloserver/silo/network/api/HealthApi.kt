package org.siloserver.silo.network.api

import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.skipSiloAuth

@Serializable
data class HealthStatus(
    val status: String,
    @SerialName("server_name")
    val serverName: String? = null,
    @SerialName("server_id")
    val serverId: String? = null,
)

open class HealthApi(private val client: HttpClient) {

    open suspend fun checkHealth(): ApiResult<HealthStatus> = safeApiCall {
        client.get("/api/v1/health") {
            // Public: never send credentials, so a dead session cannot make a
            // reachability check fail. Matches the explicit-server variants of
            // the other public endpoints.
            skipSiloAuth()
            timeout {
                connectTimeoutMillis = HEALTH_TIMEOUT_MS
                requestTimeoutMillis = HEALTH_TIMEOUT_MS
                socketTimeoutMillis = HEALTH_TIMEOUT_MS
            }
        }
    }

    private companion object {
        const val HEALTH_TIMEOUT_MS = 6_000L
    }
}
