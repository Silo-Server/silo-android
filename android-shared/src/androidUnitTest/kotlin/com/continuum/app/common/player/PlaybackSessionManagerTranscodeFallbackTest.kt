package com.continuum.app.common.player

import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackSessionResponse
import com.continuum.app.network.ApiResult
import com.continuum.app.network.AuthScopeSnapshot
import com.continuum.app.network.ContinuumJson
import com.continuum.app.network.TokenManager
import com.continuum.app.network.api.PlaybackApi
import com.continuum.app.repository.PlaybackRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackSessionManagerTranscodeFallbackTest {
    @Test
    fun remuxFallbackPreservesRemuxPlayMethodAndRequestsCopyCodecs() = runTest {
        val captured = CapturedRequest()
        val manager = manager(
            captured = captured,
            responseBody = """{"session_id":"remux-session","status":"ready","manifest_url":"/stream/remux/master","duration_seconds":120.0,"player_start_seconds":42.5}""",
        )

        val result = manager.startTranscodeFallback(
            session = session(playMethod = PlayMethod.REMUX),
            seekSeconds = 42.5,
            resolution = "1080p",
            mode = PlaybackSessionManager.TranscodeMode.REMUX,
        )

        assertTrue(result is ApiResult.Success)
        assertEquals(PlayMethod.REMUX, result.data.playMethod)
        assertEquals("/stream/remux/master", result.data.streamUrl)
        assertEquals(42.5, result.data.position)

        val body = ContinuumJson.parseToJsonElement(captured.body).jsonObject
        assertEquals("copy", body["target_codec_video"]!!.jsonPrimitive.content)
        assertEquals("copy", body["target_codec_audio"]!!.jsonPrimitive.content)
        assertEquals(0, body["target_bitrate_kbps"]!!.jsonPrimitive.int)
    }

    @Test
    fun fullFallbackStillReportsTranscodePlayMethod() = runTest {
        val manager = manager(
            captured = CapturedRequest(),
            responseBody = """{"session_id":"transcode-session","status":"ready","manifest_url":"/stream/transcode/master","duration_seconds":120.0,"player_start_seconds":12.0}""",
        )

        val result = manager.startTranscodeFallback(
            session = session(playMethod = PlayMethod.DIRECT),
            seekSeconds = 12.0,
            resolution = "1080p",
            mode = PlaybackSessionManager.TranscodeMode.FULL,
        )

        assertTrue(result is ApiResult.Success)
        assertEquals(PlayMethod.TRANSCODE, result.data.playMethod)
    }

    private fun manager(
        captured: CapturedRequest,
        responseBody: String,
    ): PlaybackSessionManager {
        val client = HttpClient(
            MockEngine { request ->
                captured.body = request.body.toByteArray().decodeToString()
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(ContinuumJson) }
        }
        return PlaybackSessionManager(
            playbackRepository = PlaybackRepository(PlaybackApi(client)),
            tokenManager = NoOpTokenManager,
        )
    }

    private fun session(playMethod: PlayMethod): PlaybackSessionResponse =
        PlaybackSessionResponse(
            sessionId = "session-1",
            userId = 1,
            profileId = "profile-1",
            mediaFileId = 42,
            playMethod = playMethod,
            streamUrl = "/stream/session-1",
            durationSeconds = 120.0,
        )

    private class CapturedRequest {
        var body: String = ""
    }
}

private object NoOpTokenManager : TokenManager {
    override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
    override suspend fun getAccessToken(): String? = null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {}
    override suspend fun clearTokens() {}
    override suspend fun invalidateSession() {}
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) {}
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) {}
    override suspend fun getServerUrl(): String = ""
    override suspend fun setServerUrl(url: String) {}
    override suspend fun getCurrentServerId(): String? = null
    override suspend fun switchActiveServer(serverId: String?) {}
    override suspend fun signOutCurrentServer() {}
    override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = null
}
