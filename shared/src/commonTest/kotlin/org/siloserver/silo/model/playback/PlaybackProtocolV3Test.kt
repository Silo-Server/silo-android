package org.siloserver.silo.model.playback

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackProtocolV3Test {
    private val plan = PlaybackPlanV3(
        planId = "plan-1",
        sessionId = "session-1",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        engine = PlaybackEngineKind.MEDIA3_DIRECT,
        stream = PlaybackStreamV3(
            url = "/api/v1/playback/session-1/stream",
            protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE,
            container = "mkv",
            mimeType = "video/x-matroska",
            headers = mapOf("X-Playback-Token" to "token"),
        ),
        effectiveRecipe = PlaybackEffectiveRecipeV3(
            videoCodec = "hevc",
            audioCodec = "truehd",
            width = 3840,
            height = 2160,
            dynamicRange = "dolby_vision_p8_1",
        ),
        decisionReason = "original_compatible",
    )

    @Test
    fun missingProtocolFeatureRequiresServerUpgradeAndPreservesAllocatedSession() {
        val result = PlaybackDecisionResponseV3(sessionId = "legacy-session").validateForMedia3()
        assertEquals(PlaybackV3Validation.Incompatible("legacy-session"), result)
    }

    @Test
    fun legacyPlanShapeDecodesTolerantlyBeforeCompatibilityGate() {
        val decoded = SiloJson.decodeFromString<PlaybackDecisionResponseV3>(
            """{"session_id":"legacy-session","playback_plan":{"plan_id":"old","delivery":"original_http","engine":"media3_direct"}}""",
        )
        assertEquals("legacy-session", decoded.sessionId)
        assertEquals(null, decoded.playbackPlan)
        assertEquals(PlaybackV3Validation.Incompatible("legacy-session"), decoded.validateForMedia3())
    }

    @Test
    fun playablePlanMustUseMedia3() {
        val playable = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan,
        ).validateForMedia3()
        assertIs<PlaybackV3Validation.Playable>(playable)

        val stale = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(engine = PlaybackEngineKind.MPV_DIRECT),
        ).validateForMedia3()
        assertIs<PlaybackV3Validation.ReplanRequired>(stale)
    }

    @Test
    fun adaptationUnavailableIsTerminal() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.ADAPTATION_UNAVAILABLE,
            terminal = PlaybackTerminalV3("transcoding_disabled", "No compatible direct route.", false),
        ).validateForMedia3()
        assertEquals(
            PlaybackV3Validation.Terminal("transcoding_disabled", "No compatible direct route.", false),
            result,
        )
    }

    @Test
    fun unsupportedHeaderRefreshFailsClosed() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                stream = plan.stream.copy(
                    headerRefresh = PlaybackHeaderRefreshMode.REFRESH_ENDPOINT,
                    headerRefreshUrl = "/refresh",
                ),
            ),
        ).validateForMedia3()
        assertEquals(
            "unsupported_header_refresh",
            assertIs<PlaybackV3Validation.Terminal>(result).reason,
        )
    }

    @Test
    fun attemptKeyIsCanonicalAndOutputRouteAware() {
        val a = plan.copy(
            transformations = listOf(
                PlaybackTransformationV3("audio_adapt"),
                PlaybackTransformationV3("container_remux"),
            ),
        ).planAttemptKey(7, listOf("pcm:truehd:8", "transport_reopen"))
        val b = plan.copy(
            transformations = listOf(
                PlaybackTransformationV3("container_remux"),
                PlaybackTransformationV3("audio_adapt"),
            ),
        ).planAttemptKey(7, listOf("transport_reopen", "pcm:truehd:8"))
        assertEquals(a, b)
        assertNotEquals(a, plan.planAttemptKey(8, listOf("pcm:truehd:8", "transport_reopen")))
        assertTrue(a.matches(Regex("v3:[0-9a-f]{16}")))
    }

    @Test
    fun startRequestNeverForcesAPlayMethod() {
        val encoded = SiloJson.encodeToString(
            PlaybackStartRequestV3(
                fileId = 12,
                profileId = "profile",
                playbackAttemptId = "attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                outputRouteGeneration = 4,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )
        assertFalse(encoded.contains("play_method"))
        assertTrue(encoded.contains("\"protocol_version\":3"))
        assertTrue(encoded.contains("media3_only"))
    }
}
