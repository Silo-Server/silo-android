package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.*
import org.siloserver.silo.playback.playbackSubtitleIdentity
import kotlin.test.*

class NativeEmbeddedSubtitleTest {
    private val row = PlaybackSubtitleInventoryItemV3(
        trackId = "file:42:subtitle:0", combinedIndex = 0, source = "embedded",
        codec = "mov_text", language = "eng", label = "English", delivery = "sidecar",
        url = "/stream/session/subtitles/0.vtt",
    )
    private val plan = PlaybackPlanV3(
        planId = "native", planAttemptKey = "v3:native", sessionId = "session",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        source = PlaybackSourceDescriptorV3(container = "mp4"),
        stream = PlaybackStreamV3(url = "/stream/session", protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE, container = "mp4"),
        selectedTracks = SelectedPlaybackTracksV3(subtitle = PlaybackTrackIdentityV3(row.trackId, 0)),
        subtitle = PlaybackSubtitleDecisionV3(mode = PlaybackSubtitleModeV3.RENDER, trackId = row.trackId,
            embedded = PlaybackEmbeddedSubtitleV3(streamIndex = 2, containerTrackId = "19"), inventory = listOf(row)),
        decisionReason = "native",
    )

    @Test fun projectsExactContainerIdentityWithoutMountingFallbackUrl() {
        val session = plan.toSessionResponse("session", "profile", 42)
        val selected = session.subtitleUrls!!.single()
        assertEquals(row.url, selected.url)
        val identity = assertIs<SubtitleIdentity.Embedded>(playbackSubtitleIdentity(selected))
        assertEquals("19", identity.containerTrackId)
        assertTrue(subtitlesForVideoMediaMount(session.subtitleUrls!!, session.playbackPlan, identity).isEmpty())
        val fallback = plan.copy(subtitle = plan.subtitle.copy(embedded = null,
            artifact = PlaybackSubtitleArtifactV3(row.url!!, "text/vtt", "vtt")))
            .toSessionResponse("session", "profile", 42)
        assertIs<SubtitleIdentity.ServerSidecar>(playbackSubtitleIdentity(fallback.subtitleUrls!!.single()))
    }

    @Test fun exactNativeIdDoesNotUseStreamIndexOrMetadataFallback() {
        val identity = SubtitleIdentity.Embedded(0, SubtitleMediaIdentity(language = "eng", codecFamily = "mov_text"), "19")
        fun track(id: String, index: Int) = MountedSubtitleTrack(index, id, "English", "eng", "application/x-quicktime-tx3g", false, false)
        assertEquals(7, resolveMountedSubtitle(identity, listOf(track("2", 0), track("0:19", 7)))?.track?.index)
        assertNull(resolveMountedSubtitle(identity, listOf(track("2", 0))))
        assertNull(resolveMountedSubtitle(identity, listOf(track("19", 0), track("0:19", 1))))
        assertNull(resolveMountedSubtitle(identity, listOf(track("1:silo-subtitle:0", 0))))
    }

    @Test fun gapNumberedMatroskaTracksResolveByTrackNumberNotStreamIndex() {
        // A remux with track numbers 1 (video), 4 (audio), 5 and 6 (subtitles):
        // the second subtitle is FFmpeg stream 3 but Matroska track 6.
        val identity = SubtitleIdentity.Embedded(1, SubtitleMediaIdentity(language = "eng", codecFamily = "subrip"), "6")
        fun track(id: String, index: Int) = MountedSubtitleTrack(index, id, null, "eng", "application/x-subrip", false, false)
        val mounted = listOf(track("0:5", 0), track("0:6", 1))
        assertEquals(1, resolveMountedSubtitle(identity, mounted)?.track?.index)
        assertNull(resolveMountedSubtitle(identity, listOf(track("0:4", 0), track("0:5", 1))))
    }

    @Test fun declaredV3SidecarNeverSkipsMountForHeuristicMuxedMatch() {
        val response = plan.copy(subtitle = plan.subtitle.copy(embedded = null,
            artifact = PlaybackSubtitleArtifactV3(row.url!!, "text/vtt", "vtt")))
            .toSessionResponse("session", "profile", 42)
        val identity = playbackSubtitleIdentity(response.subtitleUrls!!.single())
        assertEquals(listOf(row.url), subtitlesForVideoMediaMount(response.subtitleUrls!!,
            response.playbackPlan, identity, preferMuxedTracks = true).map { it.url })
    }
}
