package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.network.*

const val SEQUENCED_PROGRESS_FEATURE = "sequenced_progress_v1"

/** `PlaybackCapabilities.state` on the wire. */
object PlaybackCapabilityStateV2 {
    const val AVAILABLE = "available"
}

/** `PlaybackMutation.outcome` on the wire for progress and stop receipts. */
object PlaybackMutationOutcomeV2 {
    const val APPLIED = "applied"
    const val REPLAYED = "replayed"
    const val STALE_SAMPLE = "stale_sample"
    const val STOPPED = "stopped"
    val PROGRESS = setOf(APPLIED, REPLAYED, STALE_SAMPLE)
    val STOP = setOf(STOPPED, REPLAYED)
}

/** `revision` and `deliveries` are served but never read by Android. */
@Serializable
data class PlaybackCapabilitiesV2(
    @SerialName("installation_id") val installationId: String? = null,
    val state: String,
    val allowed: Boolean,
    @SerialName("protocol_versions") val protocolVersions: List<Int>,
    val features: List<String>,
)

@Serializable
data class PlaybackSampleV2(val sequence: Long, val position: Double, @SerialName("is_paused") val isPaused: Boolean)

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
)

/** Exact serialized body is retained by the journal before this single exchange. */
fun PlaybackStartRequestV3.v2Body(installationId: String): JsonObject = JsonObject(
    SiloJson.encodeToJsonElement(this).jsonObject + mapOf(
        "installation_id" to JsonPrimitive(installationId),
        "file_id" to JsonPrimitive(fileId.toString()),
    ),
)

fun PlaybackReplanRequestV3.v2Body(installationId: String): JsonObject = JsonObject(
    SiloJson.encodeToJsonElement(this).jsonObject + ("installation_id" to JsonPrimitive(installationId)),
)

fun PlaybackRouteEventV3.v2Body(installationId: String, eventId: String): JsonObject = JsonObject(
    SiloJson.encodeToJsonElement(this).jsonObject + mapOf(
        "installation_id" to JsonPrimitive(installationId), "event_id" to JsonPrimitive(eventId),
    ),
)

class PlaybackV2Api(private val client: HttpClient, private val gate: ApiV2Gate) {
    private fun HttpResponse.expect(status: Int): HttpResponse = also { check(!it.status.isSuccess() || it.status.value == status) }

    private fun HttpResponse.identityHeaders(): Map<String, String> = call.request.headers.entries()
        .filter { it.key.equals("Authorization", true) || it.key.equals("X-Profile-Id", true) }
        .associate { it.key to it.value.single() }

    suspend fun capabilities(scope: AuthScopeSnapshot): ApiResult<PlaybackCapabilitiesV2> =
        safeApiV2Call(gate) {
            client.get("/api/v2/playback/capabilities") { authScope(scope); requireSiloAuth() }.expect(200)
        }

    suspend fun account(scope: AuthScopeSnapshot): ApiResult<Account> = safeApiV2Call(gate) {
        client.get("/api/v2/account/me") { authScope(scope); requireSiloAuth() }
    }

    /** HTTP 201 `PlaybackDecision`, returned raw so the journal can retain the exact decision. */
    suspend fun start(scope: AuthScopeSnapshot, body: JsonObject, captureHeaders: (Map<String, String>) -> Unit = {}): ApiResult<JsonObject> =
        safeApiV2Call(gate) {
            client.post("/api/v2/playback/start") {
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }.also { captureHeaders(it.identityHeaders()) }.expect(201)
        }

    suspend fun replan(scope: AuthScopeSnapshot, sessionId: String, body: JsonObject, captureHeaders: (Map<String, String>) -> Unit = {}): ApiResult<JsonObject> =
        safeApiV2Call(gate) {
            client.post {
                url { path("", "api", "v2", "playback", sessionId, "replan") }
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }.also { captureHeaders(it.identityHeaders()) }.expect(200)
        }

    /** HTTP 202 `{event_id, outcome: "accepted"}`; the receipt must echo the sent `event_id`. */
    suspend fun routeEvent(scope: AuthScopeSnapshot, body: JsonObject): ApiResult<Unit> =
        when (val result = safeApiV2Call<JsonObject>(gate) {
            client.post("/api/v2/playback/route-events") {
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }.expect(202)
        }) {
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
            is ApiResult.Success ->
                if (result.data["event_id"] == body["event_id"] && result.data["outcome"] == JsonPrimitive("accepted"))
                    ApiResult.Success(Unit)
                else ApiResult.Error(0, "invalid_receipt", "The route event receipt did not match the request.")
        }

    suspend fun progress(scope: AuthScopeSnapshot, sessionId: String,
        body: PlaybackProgressV2): ApiResult<PlaybackMutationV2> = safeApiV2Call(gate) {
        client.post {
            url { path("", "api", "v2", "playback", sessionId, "progress") }
            authScope(scope); requireSiloAuth(); singleAttempt()
            contentType(ContentType.Application.Json); setBody(body)
        }.expect(200)
    }

    /** Only an HTTP 200 stopped or replayed receipt carrying the sent stop_id confirms the stop. */
    suspend fun stop(scope: AuthScopeSnapshot, sessionId: String, body: PlaybackStopV2): ApiResult<PlaybackMutationV2> =
        when (val result = safeApiV2Call<PlaybackMutationV2>(gate) {
            client.delete {
                url { path("", "api", "v2", "playback", sessionId) }
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }.expect(200)
        }) {
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
            is ApiResult.Success ->
                if (result.data.stopId == body.stopId && result.data.outcome in PlaybackMutationOutcomeV2.STOP) result
                else ApiResult.Error(0, "invalid_receipt", "The playback stop receipt did not match the request.")
        }
}
