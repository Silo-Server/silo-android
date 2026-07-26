package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEngineKind
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackSourceDescriptorV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.PlaybackSubtitleArtifactV3
import org.siloserver.silo.model.playback.PlaybackSubtitleDecisionV3
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlaybackTimelineV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackV3SessionTest {
    @Test
    fun originalEmbeddedBitmapRenderArtifactBecomesSelectionMetadataNotASidecar() {
        val response = plan(
            mode = PlaybackSubtitleModeV3.RENDER,
            format = "dvd_subtitle",
            url = "/stream/session/subtitles/2.vtt",
        ).toSessionResponse("session", "profile", 482)

        val subtitle = response.subtitleUrls.orEmpty().single()
        assertEquals(2, subtitle.index)
        assertEquals("dvd_subtitle", subtitle.codec)
        assertEquals("embedded", subtitle.source)
        assertEquals("", subtitle.url)
    }

    @Test
    fun convertedTextArtifactRemainsAMountableServerSidecar() {
        val response = plan(
            mode = PlaybackSubtitleModeV3.CONVERT,
            format = "webvtt",
            url = "/stream/session/subtitles/2.vtt",
        ).toSessionResponse("session", "profile", 482)

        val subtitle = response.subtitleUrls.orEmpty().single()
        assertEquals("server_artifact", subtitle.source)
        assertEquals("/stream/session/subtitles/2.vtt", subtitle.url)
    }

    @Test
    fun protocolV3TimelineSemanticsSurviveTheActivePlanConversion() {
        val timeline = PlaybackTimelineV3(
            sourceStartSeconds = 120.5,
            streamOriginSeconds = 120.0,
            playerStartSeconds = 0.5,
            timelineOffsetSeconds = 120.0,
            seekWindowStartSeconds = 120.0,
            seekWindowEndSeconds = 180.0,
            canSeekAnywhere = false,
            seekRestoration = "source_position",
        )

        val response = plan(
            mode = PlaybackSubtitleModeV3.OFF,
            format = "",
            url = "",
            timeline = timeline,
        ).toSessionResponse("session", "profile", 482)
        val converted = response.playbackPlan!!.timeline

        assertEquals(timeline.sourceStartSeconds, response.position)
        assertEquals(timeline.sourceStartSeconds, converted.sourceStartSeconds)
        assertEquals(timeline.streamOriginSeconds, converted.streamOriginSeconds)
        assertEquals(timeline.playerStartSeconds, converted.playerStartSeconds)
        assertEquals(timeline.timelineOffsetSeconds, converted.timelineOffsetSeconds)
        assertEquals(timeline.seekWindowStartSeconds, converted.seekWindowStartSeconds)
        assertEquals(timeline.seekWindowEndSeconds, converted.seekWindowEndSeconds)
        assertEquals(timeline.canSeekAnywhere, converted.canSeekAnywhere)
        assertEquals(timeline.seekRestoration, converted.seekRestoration)
    }

    @Test
    fun sourceRuntimeReachesTheSessionResponse() {
        val response = plan(
            mode = PlaybackSubtitleModeV3.OFF,
            format = "",
            url = "",
            source = PlaybackSourceDescriptorV3(mediaFileId = 482, durationSeconds = 5400.0),
        ).toSessionResponse("session", "profile", 482)

        assertEquals(5400.0, response.durationSeconds)
    }

    // A server that does not know the runtime must leave the client knowing it
    // does not know. Substituting 0.0 here is what let the playback engine's
    // growing-HLS-window duration win and show a feature film as a minute.
    @Test
    fun unknownSourceRuntimeStaysUnknown() {
        val response = plan(
            mode = PlaybackSubtitleModeV3.OFF,
            format = "",
            url = "",
            source = PlaybackSourceDescriptorV3(mediaFileId = 482),
        ).toSessionResponse("session", "profile", 482)

        assertNull(response.durationSeconds)
    }

    // Servers predating the descriptor omit it entirely; decoding must not fail
    // and the runtime must read as unknown rather than zero.
    @Test
    fun planWithoutASourceDescriptorDecodesWithAnUnknownRuntime() {
        val decoded = SiloJson.decodeFromString<PlaybackPlanV3>(
            """
            {
              "plan_id": "plan",
              "delivery": "original_http",
              "engine": "media3_direct",
              "stream": {"url": "/stream/session", "protocol": "http_progressive"},
              "decision_reason": "test"
            }
            """.trimIndent(),
        )

        assertNull(decoded.source.durationSeconds)
        assertNull(decoded.toSessionResponse("session", "profile", 482).durationSeconds)
    }

    private fun plan(
        mode: PlaybackSubtitleModeV3,
        format: String,
        url: String,
        timeline: PlaybackTimelineV3 = PlaybackTimelineV3(),
        source: PlaybackSourceDescriptorV3 = PlaybackSourceDescriptorV3(),
    ) = PlaybackPlanV3(
        source = source,
        planId = "plan",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        engine = PlaybackEngineKind.MEDIA3_DIRECT,
        stream = PlaybackStreamV3(
            url = "/stream/session",
            protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE,
            container = "mkv",
        ),
        timeline = timeline,
        selectedTracks = SelectedPlaybackTracksV3(
            subtitle = PlaybackTrackIdentityV3("subtitle", 2),
        ),
        subtitle = PlaybackSubtitleDecisionV3(
            mode = mode,
            trackId = "subtitle",
            artifact = PlaybackSubtitleArtifactV3(
                url = url,
                mimeType = "text/vtt",
                format = format,
            ),
        ),
        decisionReason = "test",
    )
}
