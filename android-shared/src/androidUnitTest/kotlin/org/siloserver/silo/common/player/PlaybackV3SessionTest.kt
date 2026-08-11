package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackSourceDescriptorV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.PlaybackSubtitleArtifactV3
import org.siloserver.silo.model.playback.PlaybackSubtitleDecisionV3
import org.siloserver.silo.model.playback.PlaybackSubtitleInventoryItemV3
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlaybackTimelineV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackV3SessionTest {
    @Test
    fun offPlanProjectsTheCompleteAuthoritativeInventoryForPhoneAndTv() {
        val response = plan(
            mode = PlaybackSubtitleModeV3.OFF,
            format = "",
            url = "",
            inventory = listOf(
                PlaybackSubtitleInventoryItemV3(
                    trackId = "external",
                    combinedIndex = 0,
                    source = "external",
                    codec = "srt",
                    language = "eng",
                    label = "English",
                    delivery = "sidecar",
                    url = "/subtitles/0.vtt",
                ),
                PlaybackSubtitleInventoryItemV3(
                    trackId = "bitmap",
                    combinedIndex = 1,
                    source = "embedded",
                    codec = "dvd_subtitle",
                    language = "fra",
                    label = "French",
                    delivery = "burn_in_only",
                ),
                PlaybackSubtitleInventoryItemV3(
                    trackId = "provider",
                    combinedIndex = 2,
                    source = "downloaded",
                    codec = "ass",
                    language = "spa",
                    label = "Spanish",
                    forced = true,
                    delivery = "sidecar",
                    url = "/subtitles/2.ass",
                ),
            ),
        ).toSessionResponse("session", "profile", 482)

        val rows = response.subtitleUrls.orEmpty()
        assertEquals(listOf(0, 1, 2), rows.map(PlayerSubtitleInfo::index))
        assertEquals(listOf("/subtitles/0.vtt", "", "/subtitles/2.ass"), rows.map(PlayerSubtitleInfo::url))
        assertEquals("bitmap", rows[1].serverTrackId)
        assertEquals("burn_in_only", rows[1].serverDelivery)
        assertEquals("downloaded", rows[2].source)
        assertTrue(rows[2].forced == true)
        assertTrue(!rows[2].isDownloadedSubtitleArtifact())
    }

    @Test
    fun unknownSubtitleDeliveriesStayOutOfTheNativePicker() {
        val response = plan(
            mode = PlaybackSubtitleModeV3.OFF,
            format = "",
            url = "",
            inventory = listOf(
                PlaybackSubtitleInventoryItemV3(
                    trackId = "future",
                    combinedIndex = 0,
                    source = "embedded",
                    codec = "future_codec",
                    delivery = "future_delivery",
                ),
            ),
        ).toSessionResponse("session", "profile", 482)

        assertTrue(response.subtitleUrls.orEmpty().isEmpty())
    }

    @Test
    fun burnInPlanKeepsInventorySelectableButDoesNotMountAlternatives() {
        val response = plan(
            mode = PlaybackSubtitleModeV3.BURN_IN,
            format = "",
            url = "",
            inventory = listOf(
                PlaybackSubtitleInventoryItemV3(
                    trackId = "text",
                    combinedIndex = 0,
                    source = "external",
                    codec = "srt",
                    delivery = "sidecar",
                    url = "/subtitles/0.srt",
                ),
                PlaybackSubtitleInventoryItemV3(
                    trackId = "bitmap",
                    combinedIndex = 1,
                    source = "embedded",
                    codec = "dvd_subtitle",
                    delivery = "burn_in_only",
                ),
            ),
        ).toSessionResponse("session", "profile", 482)

        val rows = response.subtitleUrls.orEmpty()
        assertEquals(listOf(0, 1), rows.map(PlayerSubtitleInfo::index))
        assertTrue(rows.all { it.url.isEmpty() })
    }

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
    fun unresolvedArtifactDoesNotCollideWithAuthoritativeIndexZero() {
        val inventory = listOf(
            PlaybackSubtitleInventoryItemV3(
                trackId = "catalog-zero",
                combinedIndex = 0,
                source = "external",
                codec = "srt",
                delivery = "sidecar",
                url = "/subtitles/0.vtt",
            ),
        )
        val incomplete = plan(
            mode = PlaybackSubtitleModeV3.CONVERT,
            format = "vtt",
            url = "/artifact.vtt",
            inventory = inventory,
        ).copy(selectedTracks = SelectedPlaybackTracksV3(subtitle = null))

        val rows = incomplete.toSessionResponse("session", "profile", 482).subtitleUrls.orEmpty()

        assertEquals(listOf("catalog-zero"), rows.mapNotNull(PlayerSubtitleInfo::serverTrackId))
        assertTrue(rows.none { it.source == "server_artifact" })
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
    fun stableSubtitleIdentityRestoresItsAuthoritativeOrdinal() {
        val inventory = listOf(
            PlaybackSubtitleInventoryItemV3(
                trackId = "stable-track",
                combinedIndex = 7,
                source = "external",
                codec = "srt",
                delivery = "sidecar",
                url = "/subtitles/7.vtt",
            ),
        )
        val plan = plan(
            mode = PlaybackSubtitleModeV3.CONVERT,
            format = "webvtt",
            url = "/subtitles/7.vtt",
            inventory = inventory,
        ).copy(
            selectedTracks = SelectedPlaybackTracksV3(
                subtitle = PlaybackTrackIdentityV3("stable-track", index = null),
            ),
        )

        val response = plan.toSessionResponse("session", "profile", 482)

        assertEquals(7, response.playbackPlan?.selectedTracks?.subtitleIndex)
        assertEquals(7, response.subtitleUrls.orEmpty().single().index)
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
        inventory: List<PlaybackSubtitleInventoryItemV3> = emptyList(),
    ): PlaybackPlanV3 {
        val inventorySelection = inventory.firstOrNull {
            mode != PlaybackSubtitleModeV3.OFF &&
                (mode != PlaybackSubtitleModeV3.BURN_IN || it.delivery == "burn_in_only")
        }
        val selectedSubtitle = when {
            mode == PlaybackSubtitleModeV3.OFF -> null
            inventorySelection != null -> PlaybackTrackIdentityV3(
                inventorySelection.trackId,
                inventorySelection.combinedIndex,
            )
            else -> PlaybackTrackIdentityV3("subtitle", 2)
        }
        val selectedArtifact = if (
            mode == PlaybackSubtitleModeV3.CONVERT || mode == PlaybackSubtitleModeV3.RENDER
        ) {
            PlaybackSubtitleArtifactV3(
                url = url,
                mimeType = "text/vtt",
                format = format,
            )
        } else {
            null
        }
        return PlaybackPlanV3(
            source = source,
            planId = "plan",
            planAttemptKey = "v3:test:plan",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            stream = PlaybackStreamV3(
                url = "/stream/session",
                protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE,
                container = "mkv",
            ),
            timeline = timeline,
            selectedTracks = SelectedPlaybackTracksV3(subtitle = selectedSubtitle),
            subtitle = PlaybackSubtitleDecisionV3(
                mode = mode,
                trackId = selectedSubtitle?.id,
                artifact = selectedArtifact,
                inventory = inventory,
            ),
            decisionReason = "test",
        )
    }
}
