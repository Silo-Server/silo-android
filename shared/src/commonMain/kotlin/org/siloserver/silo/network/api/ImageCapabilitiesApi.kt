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
    /** `available` when the server honors `image_size` and the per-slot widths. */
    val state: String? = null,
    val sizes: List<String> = emptyList(),
    /**
     * Per-slot width parameters keyed by slot (`poster`, `backdrop`, `logo`,
     * `still`, `photo`). Absent on servers that predate them.
     */
    @SerialName("slot_width_params") val slotWidthParams: Map<String, String>? = null,
) {
    /** The slots whose widths the server honors; empty keeps today's requests. */
    val acceptedWidthSlots: Set<String>
        get() = if (state == "available") slotWidthParams?.keys.orEmpty() else emptySet()
}

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
