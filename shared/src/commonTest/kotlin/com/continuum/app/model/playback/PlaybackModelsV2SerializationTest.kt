package com.continuum.app.model.playback

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackModelsV2SerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun startRequestSerializesV1FlatFieldsAndV2Context() {
        val request = StartPlaybackRequest(
            fileId = 42,
            profileId = "p1",
            playMethod = "direct",
            audioTrackIndex = 2,
            subtitleTrackIndex = 3,
            qualityPreference = "original",
            preserveDirectAudioSelection = true,
            codecsVideo = listOf("h264"),
            codecsAudio = listOf("aac"),
            containers = listOf("mp4"),
            maxResolution = "2160p",
            clientPlaybackContext = ClientPlaybackContext(
                formFactor = "tv",
                appVersion = "0.1.0",
                engines = mapOf(
                    PlaybackEngineKind.MPV_DIRECT to EngineCapabilityEnvelope(
                        containers = listOf("mkv"),
                        subtitles = EngineSubtitleCapabilities(assStyling = true),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"codecs_video\""))
        assertTrue(encoded.contains("\"client_playback_context\""))
        assertTrue(encoded.contains("\"play_method\":\"direct\""))
        assertTrue(encoded.contains("\"quality_preference\":\"original\""))
        assertTrue(encoded.contains("\"subtitle_track_index\":3"))
        assertTrue(encoded.contains("\"preserve_direct_audio_selection\":true"))
    }

    @Test
    fun sessionResponseDecodesWithAndWithoutPlaybackPlan() {
        val legacy = json.decodeFromString<PlaybackSessionResponse>(
            """
            {
              "session_id": "s1",
              "user_id": 1,
              "media_file_id": 42,
              "play_method": "direct",
              "stream_url": "/stream/s1",
              "audio_track_index": 0
            }
            """.trimIndent(),
        )
        assertEquals(null, legacy.playbackPlan)

        val v2 = json.decodeFromString<PlaybackSessionResponse>(
            """
            {
              "session_id": "s1",
              "user_id": 1,
              "media_file_id": 42,
              "play_method": "direct",
              "stream_url": "/stream/s1",
              "audio_track_index": 0,
              "playback_plan": {
                "plan_id": "s1",
                "protocol_version": 2,
                "delivery": "original_http",
                "engine": "mpv_direct",
                "route_family": "compatibility_direct"
              }
            }
            """.trimIndent(),
        )
        assertNotNull(v2.playbackPlan)
        assertEquals(PlaybackEngineKind.MPV_DIRECT, v2.playbackPlan?.engine)
    }

    @Test
    fun incompletePlaybackPlanDegradesToNullInsteadOfFailingTheResponse() {
        // A present-but-incomplete plan (missing the required `plan_id`) must NOT
        // throw and fail the ENTIRE session-start decode — it degrades to null so
        // the client falls back to legacy V1 routing rather than refusing playback.
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
                "engine": "mpv_direct",
                "route_family": "compatibility_direct"
              }
            }
            """.trimIndent(),
        )
        assertEquals("s1", decoded.sessionId)
        assertEquals(null, decoded.playbackPlan)
    }

    @Test
    fun transcodeStartResponseModelsStreamOriginSeconds() {
        val decoded = json.decodeFromString<TranscodeStartResponse>(
            """
            {
              "session_id": "s1",
              "status": "ready",
              "manifest_url": "/playback/transcode/s1/master.m3u8",
              "player_start_seconds": 12.0,
              "stream_origin_seconds": 10.0,
              "timeline_offset_seconds": 2.0,
              "can_seek_anywhere": false
            }
            """.trimIndent(),
        )

        assertEquals(10.0, decoded.streamOriginSeconds)
    }
}
