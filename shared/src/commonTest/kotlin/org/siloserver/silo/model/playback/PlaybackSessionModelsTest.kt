package org.siloserver.silo.model.playback

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PlaybackSessionResponse] is no longer a wire type: the neutral v3 contract
 * returns `PlaybackDecisionResponseV3`, and this model is built in-process by
 * `PlaybackV3Session.toSessionResponse` as a UI view of the plan. Its
 * serializers still matter because the subtitle models round-trip through
 * saved state and local caches, so they are what is covered here.
 */
class PlaybackSessionModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun playerSubtitleInfoPreservesRealDownloadedSubtitleId() {
        val subtitle = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "webvtt",
            label = "Downloaded English",
            source = "downloaded",
            forced = false,
            url = "/stream/s1/subtitles/4.vtt",
            downloadId = 312,
        )

        val encoded = json.encodeToString(subtitle)
        val decoded = json.decodeFromString<PlayerSubtitleInfo>(encoded)

        assertTrue(encoded.contains("\"download_id\":312"))
        assertEquals(312, decoded.downloadId)
    }

    @Test
    fun playerSubtitleInfoWithoutDownloadIdRemainsDecodable() {
        val decoded = json.decodeFromString<PlayerSubtitleInfo>(
            """
            {
              "index": 4,
              "language": "en",
              "source": "downloaded",
              "url": "/stream/s1/subtitles/4.vtt"
            }
            """.trimIndent(),
        )

        assertNull(decoded.downloadId)
    }

    @Test
    fun incompletePlaybackPlanDegradesToNullInsteadOfFailingTheResponse() {
        // A present-but-incomplete plan (missing the required `plan_id`) must NOT
        // throw and fail the ENTIRE decode. There is no legacy protocol left to
        // fall back to, so degrading to a null plan is what lets the caller
        // surface a replan instead of losing the whole session object.
        val decoded = json.decodeFromString<PlaybackSessionResponse>(
            """
            {
              "session_id": "s1",
              "user_id": 1,
              "media_file_id": 42,
              "play_method": "direct",
              "stream_url": "/stream/s1",
              "audio_track_index": 0,
              "playback_plan": {
                "delivery": "original_http",
                "route_family": "compatibility_direct"
              }
            }
            """.trimIndent(),
        )
        assertEquals("s1", decoded.sessionId)
        assertNull(decoded.playbackPlan)
    }
}
