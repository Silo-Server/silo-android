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
    private fun HttpResponse.identityHeaders(): Map<String, String> = call.request.headers.entries()
        .filter { it.key.equals("Authorization", true) || it.key.equals("X-Profile-Id", true) }
        .associate { it.key to it.value.single() }

    /** One unguarded v2 playback exchange whose success status must be [expected]. */
    private suspend inline fun <reified T, R> exchange(
        expected: HttpStatusCode, crossinline block: suspend () -> HttpResponse, crossinline project: (T) -> R,
    ): ApiResult<R> = ownedV2Call<T, R>(gate, null, null, OwnerPolicy.FULL, expected, { block() }, project)

    suspend fun capabilities(scope: AuthScopeSnapshot): ApiResult<PlaybackCapabilitiesV2> =
        exchange<PlaybackCapabilitiesV2, PlaybackCapabilitiesV2>(HttpStatusCode.OK, {
            client.get("/api/v2/playback/capabilities") { authScope(scope); requireSiloAuth() }
        }) { it }

    suspend fun account(scope: AuthScopeSnapshot): ApiResult<Account> = safeApiV2Call(gate) {
        client.get("/api/v2/account/me") { authScope(scope); requireSiloAuth() }
    }

    /** HTTP 201 `PlaybackDecision`, returned raw so the journal can retain the exact decision. */
    suspend fun start(scope: AuthScopeSnapshot, body: JsonObject, captureHeaders: (Map<String, String>) -> Unit = {}): ApiResult<JsonObject> =
        exchange<JsonObject, JsonObject>(HttpStatusCode.Created, {
            client.post("/api/v2/playback/start") {
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }.also { captureHeaders(it.identityHeaders()) }
        }) { it }

    suspend fun replan(scope: AuthScopeSnapshot, sessionId: String, body: JsonObject, captureHeaders: (Map<String, String>) -> Unit = {}): ApiResult<JsonObject> =
        exchange<JsonObject, JsonObject>(HttpStatusCode.OK, {
            client.post {
                url { path("", "api", "v2", "playback", sessionId, "replan") }
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }.also { captureHeaders(it.identityHeaders()) }
        }) { it }

    /** HTTP 202 `{event_id, outcome: "accepted"}`; the receipt must echo the sent `event_id`. */
    suspend fun routeEvent(scope: AuthScopeSnapshot, body: JsonObject): ApiResult<Unit> =
        exchange<JsonObject, Unit>(HttpStatusCode.Accepted, {
            client.post("/api/v2/playback/route-events") {
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }
        }) { receipt ->
            require(receipt["event_id"] == body["event_id"] && receipt["outcome"] == JsonPrimitive("accepted")) {
                "The route event receipt did not match the request."
            }
        }

    suspend fun progress(scope: AuthScopeSnapshot, sessionId: String,
        body: PlaybackProgressV2): ApiResult<PlaybackMutationV2> =
        exchange<PlaybackMutationV2, PlaybackMutationV2>(HttpStatusCode.OK, {
            client.post {
                url { path("", "api", "v2", "playback", sessionId, "progress") }
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }
        }) { it }

    /** Only an HTTP 200 stopped or replayed receipt carrying the sent stop_id confirms the stop. */
    suspend fun stop(scope: AuthScopeSnapshot, sessionId: String, body: PlaybackStopV2): ApiResult<PlaybackMutationV2> =
        exchange<PlaybackMutationV2, PlaybackMutationV2>(HttpStatusCode.OK, {
            client.delete {
                url { path("", "api", "v2", "playback", sessionId) }
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }
        }) { receipt ->
            // "stopped" echoes this request's stop_id. "replayed" is the stored receipt of whichever stop
            // won first (an earlier client stop, or the server's own expiry stop), so its stop_id is the
            // winner's; either way the session is over and this stop is settled.
            require(
                (receipt.outcome == PlaybackMutationOutcomeV2.STOPPED && receipt.stopId == body.stopId) ||
                    receipt.outcome == PlaybackMutationOutcomeV2.REPLAYED,
            ) { "The playback stop receipt did not match the request." }
            receipt
        }
}
