package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.network.*
import org.siloserver.silo.network.localroute.readBodyUnbuffered
import org.siloserver.silo.network.localroute.stayOnSavedAddress

const val SEQUENCED_PROGRESS_FEATURE = "sequenced_progress_v1"

/** The redirects a bandwidth probe download follows once; the server sends 307. */
private val PROBE_REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** The wait a 429 implies when its `Retry-After` is missing or unreadable (an HTTP date, say). */
private const val DEFAULT_PROBE_RETRY_AFTER_SECONDS = 30

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
}

/** `revision` and `deliveries` are served but never read by Android. */
@Serializable
data class PlaybackCapabilitiesV2(
    @SerialName("installation_id") val installationId: String? = null,
    val state: String,
    val allowed: Boolean,
    @SerialName("protocol_versions") val protocolVersions: List<Int>,
    val features: List<String>,
    /**
     * The bandwidth probe on the path this client's media takes, sent with
     * [CONNECTION_SPEED_V1_FEATURE][org.siloserver.silo.model.playback.CONNECTION_SPEED_V1_FEATURE].
     * Stable and authenticated; a download of it may redirect to a
     * short-lived proxy URL ([PlaybackV2Api.bandwidthProbe]).
     */
    @SerialName("bandwidth_probe_url") val bandwidthProbeUrl: String? = null,
    /**
     * The rate a quick test has to prove: the highest bitrate the server would
     * plan for this viewer, plus 25%. Absent when the server has none, for
     * example before any video is scanned.
     */
    @SerialName("bandwidth_probe_target_kbps") val bandwidthProbeTargetKbps: Int? = null,
    /** False when the admin turned automatic tests off; **Test speed** still works. Absent reads as on. */
    @SerialName("bandwidth_probe_automatic") val bandwidthProbeAutomatic: Boolean = true,
    /** How long a reading stays current. */
    @SerialName("bandwidth_probe_max_age_seconds") val bandwidthProbeMaxAgeSeconds: Long? = null,
)

/**
 * `POST /api/v2/playback/bandwidth-probe/results`: one probe measurement from
 * this device, which the server keeps per learned-limit key and shows in
 * Settings and on the admin device page.
 */
@Serializable
data class PlaybackBandwidthProbeResultV2(
    val kbps: Int,
    val bytes: Long,
    @SerialName("duration_ms") val durationMs: Int,
    /** `wifi`, `ethernet`, `cellular` or `other`. */
    val transport: String,
    /**
     * True when the link is at least [kbps]: a quick test that reached its
     * target, or a rate capped at 1,000,000. Null, and so omitted, otherwise:
     * the server reads absent as false.
     */
    @SerialName("lower_bound") val lowerBound: Boolean? = null,
    /**
     * True when the viewer ran this test with **Test speed**; null, and so
     * omitted, for an automatic test. A manual result above the learned limit
     * on this connection clears the limit, as Settings → Reset does.
     */
    val manual: Boolean? = null,
)

/** How a bandwidth probe download answered ([PlaybackV2Api.bandwidthProbe]). */
sealed interface BandwidthProbeResponseV2 {
    /** HTTP 429: the server allows another probe after [retryAfterSeconds]. */
    data class RateLimited(val retryAfterSeconds: Int) : BandwidthProbeResponseV2

    /**
     * Any other refusal: a non-200 status, a redirect without a usable
     * `Location` or a second redirect, or a server this build must not call
     * ([status] 0).
     */
    data class Unavailable(val status: Int) : BandwidthProbeResponseV2

    /** HTTP 200: [read] fills a buffer with the next bytes and returns the count, or -1 at the end. */
    class Body(val read: suspend (ByteArray) -> Int) : BandwidthProbeResponseV2
}

/**
 * `GET /api/v2/playback/connection`: this device's state on the connection it
 * is using now. Served only by a server advertising
 * [BUFFERING_QUALITY_V1_FEATURE][org.siloserver.silo.model.playback.BUFFERING_QUALITY_V1_FEATURE].
 * Phase 1 servers send [connection] and [learnedLimit]; phase 2 adds [probe],
 * [playbackSample] and [autoBudgetKbps], which the server computes and clients
 * only display. The server sends the latest probe and sample whether or not
 * they are current; clients judge that against the reading lifetime.
 */
@Serializable
data class PlaybackConnectionV2(
    /** The connection label, such as `local` or `remote`. */
    val connection: String,
    /** Absent when no limit is stored for this device on this connection. */
    @SerialName("learned_limit") val learnedLimit: PlaybackLearnedLimitV2? = null,
    val probe: PlaybackConnectionProbeV2? = null,
    /** The highest rate this device's playback received data at on this connection; a lower bound. */
    @SerialName("playback_sample") val playbackSample: PlaybackConnectionSampleV2? = null,
    @SerialName("auto_budget_kbps") val autoBudgetKbps: Int? = null,
)

/** The limit Auto plans under after buffering lowered it; RFC 3339 timestamps. */
@Serializable
data class PlaybackLearnedLimitV2(
    val kbps: Int,
    @SerialName("observed_at") val observedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** The latest bandwidth probe result reported from this device (phase 2). */
@Serializable
data class PlaybackConnectionProbeV2(
    val kbps: Int,
    @SerialName("measured_at") val measuredAt: String? = null,
    /** `wifi`, `ethernet`, `cellular` or `other`. */
    val transport: String? = null,
    /** The link is at least [kbps]: a quick test that reached its target, or a capped rate. */
    @SerialName("lower_bound") val lowerBound: Boolean = false,
)

/** A playback sample the server kept for this device on this connection; RFC 3339 time. */
@Serializable
data class PlaybackConnectionSampleV2(
    val kbps: Int,
    @SerialName("measured_at") val measuredAt: String? = null,
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

    /**
     * HTTP 200 [PlaybackConnectionV2] for the calling device. The auth plugin
     * attaches `X-Silo-Device-Id`, which the server keys the state on. Exists
     * only on a server advertising `buffering_quality_v1`.
     */
    suspend fun connection(scope: AuthScopeSnapshot): ApiResult<PlaybackConnectionV2> =
        exchange<PlaybackConnectionV2, PlaybackConnectionV2>(HttpStatusCode.OK, {
            client.get("/api/v2/playback/connection") { authScope(scope); requireSiloAuth() }
        }) { it }

    /** HTTP 204: forgets this device's learned limit on its current connection. */
    suspend fun resetLearnedLimit(scope: AuthScopeSnapshot): ApiResult<Unit> =
        exchange<Unit, Unit>(HttpStatusCode.NoContent, {
            client.delete("/api/v2/playback/connection/learned-limit") { authScope(scope); requireSiloAuth() }
        }) { it }

    /** Redirects are followed by hand in [bandwidthProbe], so this client follows none. */
    private val probeClient: HttpClient by lazy { client.config { followRedirects = false } }

    /**
     * Downloads the advertised bandwidth probe [url] (`bandwidth_probe_url`
     * plus `size`), hands the answer to [read], and ends the transfer when
     * [read] returns: the server stops at its next write.
     *
     * The advertised URL is the server's own and goes out with this app's
     * normal auth, pinned to [scope]. It may answer with a redirect to a
     * short-lived proxy URL that carries its own token and can be another
     * host, so that one hop is followed with none of this app's credentials.
     * Exceptions (no access token, a failed connection) propagate.
     */
    suspend fun <T> bandwidthProbe(
        scope: AuthScopeSnapshot,
        url: String,
        read: suspend (BandwidthProbeResponseV2) -> T,
    ): T {
        if (gate.blocked() != null) return read(BandwidthProbeResponseV2.Unavailable(0))
        return probeClient.prepareGet(url) {
            authScope(scope); requireSiloAuth(); acceptProbeBytes()
        }.execute { response ->
            val location = response.headers[HttpHeaders.Location]
            if (response.status.value !in PROBE_REDIRECT_STATUSES) return@execute probeAnswer(response, read)
            if (location.isNullOrBlank()) return@execute read(BandwidthProbeResponseV2.Unavailable(response.status.value))
            val target = URLBuilder(response.call.request.url).apply {
                parameters.clear()
                takeFrom(location)
            }.buildString()
            probeClient.prepareGet(target) { skipSiloAuth(); acceptProbeBytes() }
                .execute { proxied -> probeAnswer(proxied, read) }
        }
    }

    private fun HttpRequestBuilder.acceptProbeBytes() {
        header(HttpHeaders.Accept, ContentType.Application.OctetStream.toString())
        header(HttpHeaders.CacheControl, "no-store")
        // The probe's clock starts at the first body byte, so the local route
        // must not read the body ahead first.
        readBodyUnbuffered()
    }

    private suspend fun <T> probeAnswer(response: HttpResponse, read: suspend (BandwidthProbeResponseV2) -> T): T =
        when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.bodyAsChannel()
                read(BandwidthProbeResponseV2.Body { buffer -> body.readAvailable(buffer, 0, buffer.size) })
            }
            // A value under 1 second waits 1 second.
            HttpStatusCode.TooManyRequests -> read(
                BandwidthProbeResponseV2.RateLimited(
                    response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
                        ?.coerceIn(1L, Int.MAX_VALUE.toLong())?.toInt()
                        ?: DEFAULT_PROBE_RETRY_AFTER_SECONDS,
                ),
            )
            else -> read(BandwidthProbeResponseV2.Unavailable(response.status.value))
        }

    /** HTTP 204: stores this device's latest probe measurement for its current connection. */
    suspend fun reportBandwidthProbe(scope: AuthScopeSnapshot, result: PlaybackBandwidthProbeResultV2): ApiResult<Unit> =
        exchange<Unit, Unit>(HttpStatusCode.NoContent, {
            client.post("/api/v2/playback/bandwidth-probe/results") {
                authScope(scope); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(result)
            }
        }) { it }

    suspend fun account(scope: AuthScopeSnapshot): ApiResult<Account> = safeApiV2Call(gate) {
        client.get("/api/v2/account/me") { authScope(scope); requireSiloAuth() }
    }

    /**
     * HTTP 201 `PlaybackDecision`, returned raw so the journal can retain the exact decision.
     *
     * The server picks the session's local or remote bitrate cap from the
     * network this request arrives through, so it takes the local route only
     * when [allowsLocalRoute]: when the stream's media may take it too.
     */
    suspend fun start(scope: AuthScopeSnapshot, body: JsonObject, allowsLocalRoute: Boolean = false,
        captureHeaders: (Map<String, String>) -> Unit = {}): ApiResult<JsonObject> =
        exchange<JsonObject, JsonObject>(HttpStatusCode.Created, {
            client.post("/api/v2/playback/start") {
                authScope(scope); requireSiloAuth(); singleAttempt()
                if (!allowsLocalRoute) stayOnSavedAddress()
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

    /**
     * Unlike the other session mutations, progress may be refreshed and resent
     * once after a 401. Otherwise an access token that expires mid-session
     * fails every tick until unrelated traffic happens to refresh it. The
     * resend carries the same sequenced sample, which the server settles as
     * `replayed` at worst.
     */
    suspend fun progress(scope: AuthScopeSnapshot, sessionId: String,
        body: PlaybackProgressV2): ApiResult<PlaybackMutationV2> =
        exchange<PlaybackMutationV2, PlaybackMutationV2>(HttpStatusCode.OK, {
            client.post {
                url { path("", "api", "v2", "playback", sessionId, "progress") }
                authScope(scope); requireSiloAuth()
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
