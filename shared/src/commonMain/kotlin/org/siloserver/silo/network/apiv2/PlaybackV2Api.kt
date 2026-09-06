package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.network.*

const val SEQUENCED_PROGRESS_FEATURE = "sequenced_progress_v1"

@Serializable
data class PlaybackCapabilitiesV2(
    @SerialName("installation_id") val installationId: String? = null,
    val revision: String,
    val state: String,
    val allowed: Boolean,
    @SerialName("protocol_versions") val protocolVersions: List<Int>,
    val features: List<String>,
    val deliveries: List<String>,
)

@Serializable
data class PlaybackSampleV2(val sequence: Long, val position: Double,
    @SerialName("is_paused") val isPaused: Boolean)

@Serializable
data class PlaybackProgressV2(
    @SerialName("installation_id") val installationId: String,
    val sequence: Long,
    val position: Double,
    @SerialName("is_paused") val isPaused: Boolean,
)

@Serializable
data class PlaybackStopV2(
    @SerialName("installation_id") val installationId: String,
    @SerialName("stop_id") val stopId: String,
    val sequence: Long? = null,
    val position: Double? = null,
    @SerialName("is_paused") val isPaused: Boolean? = null,
)

@Serializable
data class PlaybackMutationV2(
    val outcome: String,
    val accepted: PlaybackSampleV2? = null,
    @SerialName("stop_id") val stopId: String? = null,
    @SerialName("history_id") val historyId: String? = null,
)

data class PlaybackStopReplyV2(val status: Int, val receipt: PlaybackMutationV2) {
    val terminal: Boolean get() = status == 200 && receipt.outcome in setOf("stopped", "replayed")
}

/** Exact serialized body is retained by the journal before this single exchange. */
fun PlaybackStartRequestV3.v2Body(installationId: String): JsonObject = JsonObject(
    SiloJson.encodeToJsonElement(this).jsonObject + mapOf(
        "installation_id" to JsonPrimitive(installationId),
        "file_id" to JsonPrimitive(fileId.toString()),
    ),
)

class PlaybackV2Api(private val client: HttpClient) {
    suspend fun capabilities(scope: AuthScopeSnapshot): ApiResult<PlaybackCapabilitiesV2> =
        safeApiV2Call(ApiV2Gate.Unrestricted) {
            client.get("/api/v2/playback/capabilities") { authScope(scope); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status.value == 200) }
        }

    suspend fun account(scope: AuthScopeSnapshot): ApiResult<Account> = safeApiV2Call(ApiV2Gate.Unrestricted) {
        client.get("/api/v2/account/me") { authScope(scope); requireSiloAuth() }
    }

    suspend fun start(scope: AuthScopeSnapshot, body: JsonObject): ApiResult<JsonObject> =
        safeApiV2Call(ApiV2Gate.Unrestricted) {
            client.post("/api/v2/playback/start") {
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }.also { check(!it.status.isSuccess() || it.status.value == 201) }
        }

    suspend fun progress(scope: AuthScopeSnapshot, sessionId: String,
        body: PlaybackProgressV2): ApiResult<PlaybackMutationV2> = safeApiV2Call(ApiV2Gate.Unrestricted) {
        client.post {
            url { path("api", "v2", "playback", sessionId, "progress") }
            authScope(scope); requireSiloAuth(); singleAttempt()
            contentType(ContentType.Application.Json); setBody(body)
        }.also { check(!it.status.isSuccess() || it.status.value == 200) }
    }

    suspend fun stop(scope: AuthScopeSnapshot, sessionId: String, body: PlaybackStopV2): ApiResult<PlaybackStopReplyV2> {
        var status = 0
        val result = safeApiV2Call<PlaybackMutationV2>(ApiV2Gate.Unrestricted) {
            client.delete {
                url { path("api", "v2", "playback", sessionId) }
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }.also {
                status = it.status.value
                check(!it.status.isSuccess() || status in setOf(200, 202))
            }
        }
        return when (result) {
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
            is ApiResult.Success -> {
            val receipt = result.data
            if (receipt.stopId != body.stopId ||
                (status == 202 && receipt.outcome != "draining") ||
                (status == 200 && receipt.outcome !in setOf("stopped", "replayed")))
                ApiResult.Error(0, "invalid_receipt", "The playback stop receipt did not match the request.")
            else ApiResult.Success(PlaybackStopReplyV2(status, receipt))
            }
        }
    }
}
