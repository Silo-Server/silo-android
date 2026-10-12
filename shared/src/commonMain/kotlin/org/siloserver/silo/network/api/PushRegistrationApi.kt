package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.notifications.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.*

interface PushRegistrationApi {
    suspend fun available(owner: AuthScopeSnapshot): ApiResult<Boolean>
    suspend fun register(request: PushDeviceRegisterRequest, key: String, generation: Long, owner: AuthScopeSnapshot): ApiResult<PushDeviceRegisterResponse>
    suspend fun delete(deviceId: String, key: String, generation: Long, owner: AuthScopeSnapshot): ApiResult<Unit>
}

class DefaultPushRegistrationApi(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) : PushRegistrationApi {
    /** Push registrations belong to a durable login only: never a temporary remote-playback overlay, never no profile. */
    private fun durable(owner: AuthScopeSnapshot): Boolean = owner.credentialGenerationId == null && !owner.profileId.isNullOrBlank()

    /**
     * A generation-pinned saved login can name a server that is not active (removing
     * its registration before sign-out or removal): gated on that server and guarded by
     * the token manager at send time rather than compared with the active scope.
     */
    private fun gateFor(owner: AuthScopeSnapshot) = if (owner.isGenerationPinned) gate.forServer(owner.serverId) else gate
    private fun guardOf(owner: AuthScopeSnapshot) = owner.takeUnless { it.isGenerationPinned }

    override suspend fun available(owner: AuthScopeSnapshot): ApiResult<Boolean> {
        if (!durable(owner)) return identityChanged()
        return ownedV2Call<JsonObject, Boolean>(gateFor(owner), tokens, guardOf(owner), OwnerPolicy.FULL, HttpStatusCode.OK, {
            client.get("$DEVICES_PATH/capabilities") { authScope(owner); requireSiloAuth() }
        }) { body ->
            body["state"] == JsonPrimitive("available") && body["allowed"] != JsonPrimitive(false) &&
                body["registration_available"] == JsonPrimitive(true) &&
                (body["platforms"] as? JsonArray)?.contains(JsonPrimitive("android")) == true
        }
    }
    override suspend fun register(request: PushDeviceRegisterRequest, key: String, generation: Long, owner: AuthScopeSnapshot): ApiResult<PushDeviceRegisterResponse> {
        if (!durable(owner)) return identityChanged()
        return ownedV2Call<JsonObject, PushDeviceRegisterResponse>(gateFor(owner), tokens, guardOf(owner), OwnerPolicy.FULL, HttpStatusCode.OK, {
            client.post(DEVICES_PATH) {
                authScope(owner); requireSiloAuth(); singleAttempt()
                header("X-Push-Installation-Key", key); header("X-Push-Generation", generation.toString())
                contentType(ContentType.Application.Json); setBody(request)
            }
        }) { body ->
            fun string(name: String): String = (body[name] as? JsonPrimitive)
                ?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: throw IllegalArgumentException("Push registration acknowledgment is invalid.")
            require(string("generation") == generation.toString() && string("push_mode") == request.pushMode) { "Push registration acknowledgment is invalid." }
            // Optional: a malformed identity only costs push routing, never the registration.
            val serverId = (body["server_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.length in 1..128 }
            PushDeviceRegisterResponse(string("registration_id"), request.pushMode, string("generation"), string("server_device_id"), serverId)
        }
    }
    override suspend fun delete(deviceId: String, key: String, generation: Long, owner: AuthScopeSnapshot): ApiResult<Unit> {
        if (!durable(owner)) return identityChanged()
        return ownedV2Call<Unit, Unit>(gateFor(owner), tokens, guardOf(owner), OwnerPolicy.FULL, HttpStatusCode.NoContent, {
            client.delete("$DEVICES_PATH/${deviceId.encodeURLPathPart()}") {
                authScope(owner); requireSiloAuth(); singleAttempt()
                header("X-Push-Installation-Key", key); header("X-Push-Generation", generation.toString())
            }
        }) { }
    }
    companion object { const val DEVICES_PATH = "/api/v2/notifications/push/devices" }
}
