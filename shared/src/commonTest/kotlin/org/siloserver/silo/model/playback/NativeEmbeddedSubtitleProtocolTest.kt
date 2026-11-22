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

    @Test fun matroskaTextTracksArePlayableOnlyForAdvertisedCodecs() {
        fun mkv(codec: String) = plan.copy(
            source = PlaybackSourceDescriptorV3(container = "mkv"),
            subtitle = plan.subtitle.copy(inventory = listOf(track.copy(codec = codec))),
        )
        assertIs<PlaybackV3Validation.Playable>(validate(mkv("subrip")))
        assertIs<PlaybackV3Validation.Playable>(validate(mkv("ass")))
        assertIs<PlaybackV3Validation.Playable>(validate(mkv("srt")))
        assertIs<PlaybackV3Validation.ReplanRequired>(validate(mkv("webvtt")))
        assertIs<PlaybackV3Validation.ReplanRequired>(validate(plan.copy(
            subtitle = plan.subtitle.copy(inventory = listOf(track.copy(codec = "subrip"))),
        )))
    }

    @Test fun matroskaBitmapTracksArePlayableUnderEveryServerAlias() {
        fun mkv(codec: String, delivery: String = "sidecar", url: String? = "/subtitle.sup") = plan.copy(
            source = PlaybackSourceDescriptorV3(container = "mkv"),
            subtitle = plan.subtitle.copy(inventory = listOf(track.copy(codec = codec, delivery = delivery, url = url))),
        )
        listOf("hdmv_pgs_subtitle", "pgs", "pgssub").forEach { codec ->
            assertIs<PlaybackV3Validation.Playable>(validate(mkv(codec)), codec)
        }
        // VobSub and DVB rows are burn_in_only in the inventory: the server has
        // no sidecar for them, so the native plan is the only client route.
        listOf("dvd_subtitle", "dvdsub", "vobsub", "dvb_subtitle", "dvbsub").forEach { codec ->
            assertIs<PlaybackV3Validation.Playable>(validate(mkv(codec, delivery = "burn_in_only", url = null)), codec)
        }
        listOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle").forEach { codec ->
            val mp4 = plan.copy(subtitle = plan.subtitle.copy(inventory = listOf(track.copy(codec = codec))))
            assertEquals(
                SUBTITLE_EMBEDDED_FAILED_CLASSIFICATION,
                assertIs<PlaybackV3Validation.ReplanRequired>(validate(mp4), codec).reason,
            )
        }
        assertIs<PlaybackV3Validation.ReplanRequired>(validate(mkv("dvb_teletext", delivery = "burn_in_only", url = null)))
    }

    @Test fun matroskaAdvertisesEveryBitmapFamilyMedia3Demuxes() {
        assertEquals(
            listOf("subrip", "ass", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle"),
            NATIVE_EMBEDDED_SUBTITLE_CODECS.getValue("mkv"),
        )
        assertEquals(listOf("mov_text"), NATIVE_EMBEDDED_SUBTITLE_CODECS.getValue("mp4"))
    }

    @Test fun nativeCapabilitiesHaveExactWireNames() {
        val capability = DeliverySubtitleCapabilities(nativeEmbedded = listOf(NativeEmbeddedSubtitleCapability("mp4", listOf("mov_text"), "container_track_id")))
        val json = SiloJson.encodeToString(capability)
        assertTrue(json.contains("native_embedded"))
        assertTrue(json.contains("track_identity"))
        assertEquals(capability, SiloJson.decodeFromString<DeliverySubtitleCapabilities>(json))
    }
}
