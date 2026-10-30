package org.siloserver.silo.model.playback

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.playback.playbackSubtitleIdentity
import kotlin.test.*

class HlsSubtitleRenditionProtocolTest {
    private val track = PlaybackSubtitleInventoryItemV3(
        "file:42:subtitle:0", 0, "embedded", codec = "subrip", delivery = "sidecar", url = "/stream/s/subtitles/0.vtt",
    )
    private val rendition = PlaybackSubtitleHlsRenditionV3("subs", "Subtitles", "vtt", "text/vtt")
    private val plan = PlaybackPlanV3(
        planId = "rendition", planAttemptKey = "v3:rendition", sessionId = "session",
        delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
        stream = PlaybackStreamV3(url = "/playback/transcode/session/master.m3u8", protocol = PlaybackStreamProtocol.HLS),
        selectedTracks = SelectedPlaybackTracksV3(subtitle = PlaybackTrackIdentityV3(track.trackId, 0)),
        subtitle = PlaybackSubtitleDecisionV3(
            mode = PlaybackSubtitleModeV3.RENDER, trackId = track.trackId, hlsRendition = rendition, inventory = listOf(track),
        ),
        decisionReason = "rendition",
    )

    private fun validate(candidate: PlaybackPlanV3) = PlaybackDecisionResponseV3(
        protocolVersion = PLAYBACK_PROTOCOL_V3,
        serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE, NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE, HLS_SUBTITLE_RENDITIONS_V1_FEATURE),
        outcome = PlaybackDecisionOutcome.PLAYABLE, playbackPlan = candidate,
    ).validateForMedia3()

    private fun withSubtitle(change: PlaybackSubtitleDecisionV3.() -> PlaybackSubtitleDecisionV3) =
        plan.copy(subtitle = plan.subtitle.change())

    @Test fun transcodeRenditionIsPlayableAndRoundTripsItsWireNames() {
        assertIs<PlaybackV3Validation.Playable>(validate(plan))
        assertIs<PlaybackV3Validation.Playable>(validate(withSubtitle { copy(mode = PlaybackSubtitleModeV3.CONVERT) }))
        val json = SiloJson.encodeToString(plan)
        assertTrue(json.contains("\"hls_rendition\":{\"group_id\":\"subs\",\"name\":\"Subtitles\",\"format\":\"vtt\",\"mime_type\":\"text/vtt\"}"))
        assertEquals(rendition, SiloJson.decodeFromString<PlaybackPlanV3>(json).subtitle.hlsRendition)
    }

    @Test fun copyRemuxRenditionIsPlayable() {
        val remux = plan.copy(delivery = PlaybackDelivery.SERVER_REMUX_HLS)
        assertIs<PlaybackV3Validation.Playable>(validate(remux))
        assertIs<PlaybackV3Validation.Playable>(validate(remux.copy(subtitle = remux.subtitle.copy(mode = PlaybackSubtitleModeV3.CONVERT))))
        assertEquals(
            listOf(PlaybackDelivery.SERVER_REMUX_HLS, PlaybackDelivery.SERVER_TRANSCODE_HLS),
            PlaybackDelivery.entries.filter { it.carriesHlsSubtitleRendition() }.sortedBy { it.name },
        )
    }

    @Test fun malformedRenditionRequestsSidecarRecovery() {
        listOf(
            plan.copy(delivery = PlaybackDelivery.ORIGINAL_HTTP),
            plan.copy(delivery = PlaybackDelivery.SERVER_REMUX_PROGRESSIVE),
            plan.copy(delivery = PlaybackDelivery.SERVER_REMUX_HLS, stream = plan.stream.copy(protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE)),
            plan.copy(stream = plan.stream.copy(protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE)),
            withSubtitle { copy(mode = PlaybackSubtitleModeV3.BURN_IN) },
            withSubtitle { copy(artifact = PlaybackSubtitleArtifactV3(track.url!!, "text/vtt", "vtt")) },
            withSubtitle { copy(hlsRendition = rendition.copy(groupId = " ")) },
            withSubtitle { copy(trackId = "file:42:subtitle:9") },
        ).forEach { candidate ->
            val result = assertIs<PlaybackV3Validation.ReplanRequired>(validate(candidate), candidate.toString())
            assertEquals(SUBTITLE_HLS_RENDITION_FAILED_CLASSIFICATION, result.reason)
        }
        // A contradictory native selection on a transcode is rejected by the embedded check first.
        assertIs<PlaybackV3Validation.ReplanRequired>(validate(withSubtitle { copy(embedded = PlaybackEmbeddedSubtitleV3(2, "3")) }))
        assertIs<PlaybackV3Validation.Terminal>(validate(plan.copy(selectedTracks = SelectedPlaybackTracksV3())))
    }

    @Test fun onlyMedia3HlsDeliveriesNegotiateRenditions() {
        fun context(features: List<String>) = ClientPlaybackContext(
            formFactor = "mobile", appVersion = "test",
            deliveries = mapOf(DELIVERY_CLASS_HLS to DeliveryCapability(features = features)),
        )
        assertTrue(HLS_SUBTITLE_RENDITIONS_V1_FEATURE in playbackClientFeaturesV3(context(listOf("hls", NATIVE_HLS_PLAYBACK_V1_FEATURE))))
        // The Cast context's HLS delivery has no native-HLS marker.
        assertFalse(HLS_SUBTITLE_RENDITIONS_V1_FEATURE in playbackClientFeaturesV3(context(listOf("hls", "buffer_reporting"))))
        assertFalse(HLS_SUBTITLE_RENDITIONS_V1_FEATURE in playbackClientFeaturesV3(context(listOf()).copy(deliveries = emptyMap())))
        assertFalse(HLS_SUBTITLE_RENDITIONS_V1_FEATURE in PLAYBACK_START_CLIENT_FEATURES_V3)
        // The remux extension needs the server's advertisement, so it is never part of the context's own list.
        assertFalse(HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE in playbackClientFeaturesV3(context(listOf("hls", NATIVE_HLS_PLAYBACK_V1_FEATURE))))
    }

    @Test fun remuxRenditionsAreRequestedOnlyWhenTheServerAdvertisesThemAndTheTranscodeFeatureIsListed() {
        val start = PlaybackStartRequestV3(
            clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3 + HLS_SUBTITLE_RENDITIONS_V1_FEATURE,
            fileId = 42, profileId = "profile", playbackAttemptId = "attempt",
            subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
            capabilities = ClientCodecCapabilities(),
            clientPlaybackContext = ClientPlaybackContext(formFactor = "mobile", appVersion = "test"),
        )
        val advertised = setOf(HLS_SUBTITLE_RENDITIONS_V1_FEATURE, HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE)
        val gated = start.gatedForHlsRemuxSubtitleRenditions(advertised)
        assertEquals(1, gated.clientFeatures.count { it == HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE })
        assertEquals(gated, gated.gatedForHlsRemuxSubtitleRenditions(advertised))
        assertFalse(HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE in gated.gatedForHlsRemuxSubtitleRenditions(setOf(HLS_SUBTITLE_RENDITIONS_V1_FEATURE)).clientFeatures)
        val cast = start.copy(clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3)
        assertEquals(cast, cast.gatedForHlsRemuxSubtitleRenditions(advertised))
    }

    @Test fun renditionRowIsAnExactServerSelectionNotASidecar() {
        val row = PlayerSubtitleInfo(
            index = 0, url = "", source = "embedded", codec = "subrip",
            serverTrackId = track.trackId, serverDelivery = SUBTITLE_DELIVERY_SIDECAR,
            hlsRendition = SubtitleHlsRendition("subs", "Subtitles"),
        )
        val identity = assertIs<SubtitleIdentity.Embedded>(playbackSubtitleIdentity(row))
        assertEquals(SubtitleHlsRendition("subs", "Subtitles"), identity.hlsRendition)
        assertNull(identity.containerTrackId)
        assertTrue(identity.isServerSelected)
        assertEquals("subs:Subtitles", identity.hlsRendition!!.media3TrackId)
        assertTrue(identity.matchesServerSelectedPlan(PlaybackSubtitleModeV3.RENDER, hasSidecar = false))
        assertTrue(identity.matchesServerSelectedPlan(PlaybackSubtitleModeV3.CONVERT, hasSidecar = false))
        assertFalse(identity.matchesServerSelectedPlan(PlaybackSubtitleModeV3.RENDER, hasSidecar = true))
        assertFalse(identity.matchesServerSelectedPlan(PlaybackSubtitleModeV3.OFF, hasSidecar = false))
        assertIs<SubtitleIdentity.ServerSidecar>(playbackSubtitleIdentity(row.copy(hlsRendition = null, url = track.url!!)))
    }
}
