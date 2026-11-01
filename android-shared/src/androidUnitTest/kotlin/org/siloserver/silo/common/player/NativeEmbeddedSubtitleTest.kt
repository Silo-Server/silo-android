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

    @Test fun nativeMatroskaBitmapPlanMountsNoSupOrBurnInFallback() {
        val pgs = row.copy(codec = "hdmv_pgs_subtitle", url = "/stream/session/subtitles/0.sup")
        val vobSub = row.copy(trackId = "file:42:subtitle:1", combinedIndex = 1, codec = "dvd_subtitle",
            delivery = "burn_in_only", url = null)
        fun native(selected: PlaybackSubtitleInventoryItemV3, containerTrackId: String) = plan.copy(
            source = PlaybackSourceDescriptorV3(container = "mkv"),
            stream = plan.stream.copy(container = "mkv"),
            selectedTracks = SelectedPlaybackTracksV3(subtitle = PlaybackTrackIdentityV3(selected.trackId, selected.combinedIndex)),
            subtitle = PlaybackSubtitleDecisionV3(mode = PlaybackSubtitleModeV3.RENDER, trackId = selected.trackId,
                embedded = PlaybackEmbeddedSubtitleV3(streamIndex = 3 + selected.combinedIndex, containerTrackId = containerTrackId),
                inventory = listOf(pgs, vobSub)),
        )
        for ((selected, id) in listOf(pgs to "4", vobSub to "5")) {
            val session = native(selected, id).toSessionResponse("session", "profile", 42)
            val projected = session.subtitleUrls!!.single { it.index == selected.combinedIndex }
            val identity = assertIs<SubtitleIdentity.Embedded>(playbackSubtitleIdentity(projected), selected.codec)
            assertEquals(id, identity.containerTrackId)
            assertTrue(projected.isMuxedInDirectPlayStream(session.playbackPlan!!), selected.codec)
            assertTrue(subtitlesForVideoMediaMount(session.subtitleUrls!!, session.playbackPlan, identity).isEmpty())
            // The server-selected row is native even while the committed
            // identity is still the sidecar or burn-in pick that asked for it.
            val requested = SubtitleIdentity.ServerSidecar(selected.combinedIndex)
            assertTrue(subtitlesForVideoMediaMount(session.subtitleUrls!!, session.playbackPlan, requested,
                preferMuxedTracks = true).isEmpty())
        }
        // Without a native decision the PGS row keeps its `.sup` sidecar and
        // VobSub stays a burn-in pick.
        val sidecar = native(pgs, "4").copy(subtitle = native(pgs, "4").subtitle.copy(embedded = null,
            artifact = PlaybackSubtitleArtifactV3(pgs.url!!, "application/pgs", "hdmv_pgs_subtitle")))
            .toSessionResponse("session", "profile", 42)
        assertIs<SubtitleIdentity.ServerSidecar>(playbackSubtitleIdentity(sidecar.subtitleUrls!!.single { it.index == 0 }))
        assertIs<SubtitleIdentity.ServerBurnIn>(playbackSubtitleIdentity(sidecar.subtitleUrls!!.single { it.index == 1 }))
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
