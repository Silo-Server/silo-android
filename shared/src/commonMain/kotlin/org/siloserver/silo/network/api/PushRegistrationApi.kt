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
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) : PushRegistrationApi {
    private suspend fun current(owner: AuthScopeSnapshot): Boolean = tokens.snapshotCurrentScope().let {
        owner.isSameIdentityAs(it) && owner.serverUrl == it?.serverUrl && owner.profileId == it?.profileId &&
            owner.profileToken == it?.profileToken && owner.credentialGenerationId == null && !owner.profileId.isNullOrBlank()
    }
    private fun changed() = ApiResult.Error(0, "push_authority_changed", "The initiating push registration identity is unavailable.")
    override suspend fun available(owner: AuthScopeSnapshot): ApiResult<Boolean> {
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get("$DEVICES_PATH/capabilities") { authScope(owner); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return result.map { body ->
            body["revision"] == JsonPrimitive("ordered_android_v1") &&
                body["registration_available"] == JsonPrimitive(true) &&
                (body["platforms"] as? JsonArray)?.contains(JsonPrimitive("android")) == true
        }
    }
    override suspend fun register(request: PushDeviceRegisterRequest, key: String, generation: Long, owner: AuthScopeSnapshot): ApiResult<PushDeviceRegisterResponse> {
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.post(DEVICES_PATH) {
                authScope(owner); requireSiloAuth(); singleAttempt()
                header("X-Push-Installation-Key", key); header("X-Push-Generation", generation.toString())
                contentType(ContentType.Application.Json); setBody(request)
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                fun string(name: String): String = (result.data[name] as? JsonPrimitive)
                    ?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: error("Invalid receipt")
                check(string("generation") == generation.toString())
                check(string("push_mode") == request.pushMode)
                ApiResult.Success(PushDeviceRegisterResponse(string("registration_id"), request.pushMode,
                    string("generation"), string("server_device_id")))
            } catch (_: IllegalStateException) { ApiResult.Error(0, "invalid_push_receipt", "Push registration acknowledgment is invalid.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    override suspend fun delete(deviceId: String, key: String, generation: Long, owner: AuthScopeSnapshot): ApiResult<Unit> {
        if (!current(owner)) return changed()
        val result = safeApiV2Call<Unit>(gate) {
            client.delete("$DEVICES_PATH/${deviceId.encodeURLPathPart()}") {
                authScope(owner); requireSiloAuth(); singleAttempt()
                header("X-Push-Installation-Key", key); header("X-Push-Generation", generation.toString())
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
        return if (current(owner)) result else changed()
    }
    companion object { const val DEVICES_PATH = "/api/v2/notifications/push/devices" }
}
