package org.siloserver.silo.model.playback

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.siloserver.silo.network.SiloJson
import kotlin.test.*

class NativeEmbeddedSubtitleProtocolTest {
    private val track = PlaybackSubtitleInventoryItemV3("file:42:subtitle:0", 0, "embedded", codec = "mov_text", delivery = "sidecar", url = "/subtitle.vtt")
    private val plan = PlaybackPlanV3(
        planId = "native", planAttemptKey = "v3:native", sessionId = "session",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        source = PlaybackSourceDescriptorV3(container = "mp4"),
        stream = PlaybackStreamV3(url = "/stream/session", protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE, container = "mp4"),
        selectedTracks = SelectedPlaybackTracksV3(subtitle = PlaybackTrackIdentityV3(track.trackId, 0)),
        subtitle = PlaybackSubtitleDecisionV3(mode = PlaybackSubtitleModeV3.RENDER, trackId = track.trackId,
            embedded = PlaybackEmbeddedSubtitleV3(2, "19"), inventory = listOf(track)),
        decisionReason = "native",
    )
    private fun validate(candidate: PlaybackPlanV3) = PlaybackDecisionResponseV3(
        protocolVersion = PLAYBACK_PROTOCOL_V3,
        serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE, NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE, EMBEDDED_SUBTITLES_V1_FEATURE),
        outcome = PlaybackDecisionOutcome.PLAYABLE, playbackPlan = candidate,
    ).validateForMedia3()

    @Test fun originalMp4TimedTextIsPlayableAndRoundTripsIdentity() {
        assertIs<PlaybackV3Validation.Playable>(validate(plan))
        val decoded = SiloJson.decodeFromString<PlaybackPlanV3>(SiloJson.encodeToString(plan))
        assertEquals(PlaybackEmbeddedSubtitleV3(2, "19"), decoded.subtitle.embedded)
        assertNull(decoded.subtitle.artifact)
    }

    @Test fun missingInvalidOrUnsupportedNativeIdentityRequestsSidecarRecovery() {
        listOf(null, "", "0", "-1", "019", "0x13", "2147483648").forEach { id ->
            val result = validate(plan.copy(subtitle = plan.subtitle.copy(embedded = PlaybackEmbeddedSubtitleV3(2, id))))
            assertEquals("subtitle_embedded_failed", assertIs<PlaybackV3Validation.ReplanRequired>(result).reason)
        }
        assertIs<PlaybackV3Validation.ReplanRequired>(validate(plan.copy(delivery = PlaybackDelivery.SERVER_REMUX_HLS)))
        assertIs<PlaybackV3Validation.ReplanRequired>(validate(plan.copy(source = PlaybackSourceDescriptorV3(container = "mkv"))))
    }

    @Test fun nativeCapabilitiesHaveExactWireNames() {
        val capability = DeliverySubtitleCapabilities(nativeEmbedded = listOf(NativeEmbeddedSubtitleCapability("mp4", listOf("mov_text"), "container_track_id")))
        val json = SiloJson.encodeToString(capability)
        assertTrue(json.contains("native_embedded"))
        assertTrue(json.contains("track_identity"))
        assertEquals(capability, SiloJson.decodeFromString<DeliverySubtitleCapabilities>(json))
    }
}
