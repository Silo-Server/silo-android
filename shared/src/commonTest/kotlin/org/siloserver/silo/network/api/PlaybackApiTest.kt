package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PLAYBACK_START_CLIENT_FEATURES_V3
import org.siloserver.silo.model.playback.PlaybackFailureV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackApiTest {
    private class Captured {
        var method: HttpMethod? = null
        var path: String = ""
        var body: String = ""
    }

    private fun api(captured: Captured): PlaybackApi {
        val client = HttpClient(
            MockEngine { request ->
                captured.method = request.method
                captured.path = request.url.encodedPath
                captured.body = request.body.toByteArray().decodeToString()
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return PlaybackApi(client)
    }

    private fun context() = ClientPlaybackContext(formFactor = "tv", appVersion = "test")

    @Test
    fun `v3 start uses canonical endpoint and negotiation fields`() = runTest {
        val captured = Captured()
        api(captured).startPlaybackV3(
            PlaybackStartRequestV3(
                fileId = 42,
                profileId = "profile",
                playbackAttemptId = "attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                audioTrackId = "file:42:audio:2",
                audioTrackIndex = 2,
                outputRouteGeneration = 7,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = context(),
            ),
        )

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/api/v1/playback/start", captured.path)
        val body = SiloJson.parseToJsonElement(captured.body).jsonObject
        assertEquals(3, body["protocol_version"]!!.jsonPrimitive.int)
        assertEquals("file:42:audio:2", body["audio_track_id"]!!.jsonPrimitive.content)
        assertEquals(
            PLAYBACK_START_CLIENT_FEATURES_V3,
            body["client_features"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `v3 replan is session scoped and carries loop prevention`() = runTest {
        val captured = Captured()
        api(captured).replanPlaybackV3(
            "session-1",
            PlaybackReplanRequestV3(
                playbackAttemptId = "attempt",
                replanRequestId = "request",
                failedPlanId = "plan-1",
                planAttemptId = "plan-attempt",
                planAttemptKey = "key-1",
                attemptedPlanKeys = listOf("key-0", "key-1"),
                attemptCount = 2,
                qualityPreference = "720p",
                positionSeconds = 12.5,
                outputRouteGeneration = 8,
                metered = true,
                bandwidthEstimateKbps = 18_500,
                bandwidthCapKbps = 12_000,
                selectedTracks = SelectedPlaybackTracksV3(),
                failure = PlaybackFailureV3("decoder_no_output"),
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = context(),
            ),
        )

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/api/v1/playback/session-1/replan", captured.path)
        val body = SiloJson.parseToJsonElement(captured.body).jsonObject
        assertEquals("key-1", body["plan_attempt_key"]!!.jsonPrimitive.content)
        assertEquals(2, body["attempted_plan_keys"]!!.jsonArray.size)
        assertEquals(true, body["metered"]!!.jsonPrimitive.boolean)
        assertEquals(18_500, body["bandwidth_estimate_kbps"]!!.jsonPrimitive.int)
        assertEquals(12_000, body["bandwidth_cap_kbps"]!!.jsonPrimitive.int)
    }

    @Test
    fun `v3 telemetry uses attempt scoped global endpoint`() = runTest {
        val captured = Captured()
        api(captured).reportRouteEventV3(
            PlaybackRouteEventV3(
                playbackAttemptId = "attempt",
                sessionId = "session",
                event = "plan_failed",
                outputRouteGeneration = 9,
            ),
        )

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/api/v1/playback/route-events", captured.path)
        val body = SiloJson.parseToJsonElement(captured.body).jsonObject
        assertEquals("attempt", body["playback_attempt_id"]!!.jsonPrimitive.content)
        assertEquals("plan_failed", body["event"]!!.jsonPrimitive.content)
    }
}
