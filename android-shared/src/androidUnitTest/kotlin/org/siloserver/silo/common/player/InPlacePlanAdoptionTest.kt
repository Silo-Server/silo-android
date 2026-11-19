package org.siloserver.silo.common.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.model.playback.*
import org.siloserver.silo.playback.playbackSubtitleIdentity
import kotlin.test.*

@OptIn(UnstableApi::class)
class InPlacePlanAdoptionTest {
    private fun row(
        index: Int,
        nativeSelectable: Boolean? = null,
        rendition: PlaybackSubtitleInventoryHlsRenditionV3? = null,
    ) = PlaybackSubtitleInventoryItemV3(
        trackId = "file:42:subtitle:$index", combinedIndex = index, source = "embedded", codec = "subrip",
        language = "eng", label = "Track $index", delivery = "sidecar", url = "/stream/s1/subtitles/$index.vtt",
        streamIndex = index + 2, containerTrackId = "${index + 3}",
        nativeSelectable = nativeSelectable, hlsRendition = rendition,
    )

    private val original = PlaybackPlanV3(
        planId = "plan-0", planAttemptKey = "v3:0", sessionId = "s1",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        source = PlaybackSourceDescriptorV3(container = "mkv"),
        stream = PlaybackStreamV3(url = "/api/v2/stream/s1", protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE, container = "mkv"),
        selectedTracks = SelectedPlaybackTracksV3(subtitle = PlaybackTrackIdentityV3("file:42:subtitle:0", 0)),
        subtitle = PlaybackSubtitleDecisionV3(
            mode = PlaybackSubtitleModeV3.RENDER, trackId = "file:42:subtitle:0",
            embedded = PlaybackEmbeddedSubtitleV3(streamIndex = 2, containerTrackId = "3"),
            inventory = listOf(row(0, nativeSelectable = true), row(1, nativeSelectable = true), row(2)),
        ),
        decisionReason = "validated_original_playback",
    )

    private fun rendition(index: Int) = PlaybackSubtitleInventoryHlsRenditionV3("subs", "Track $index", index + 1)

    private val transcode = PlaybackPlanV3(
        planId = "hls-0", planAttemptKey = "v3:hls-0", sessionId = "s1",
        delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
        stream = PlaybackStreamV3(url = "/playback/transcode/s1/master.m3u8", protocol = PlaybackStreamProtocol.HLS),
        selectedTracks = SelectedPlaybackTracksV3(subtitle = PlaybackTrackIdentityV3("file:42:subtitle:0", 0)),
        subtitle = PlaybackSubtitleDecisionV3(
            mode = PlaybackSubtitleModeV3.RENDER, trackId = "file:42:subtitle:0",
            hlsRendition = PlaybackSubtitleHlsRenditionV3("subs", "Track 0", "vtt", "text/vtt", index = 1),
            inventory = listOf(row(0, rendition = rendition(0)), row(1, rendition = rendition(1))),
        ),
        decisionReason = "quality_fixed_rung",
    )

    @Test fun nativeSelectableRowsAreExactNativeIdentitiesThatKeepTheirFallbackUrl() {
        val rows = original.toSessionResponse("s1", "profile", 42).subtitleUrls!!
        assertEquals(listOf("3", "4", null), rows.map { it.nativeContainerTrackId })
        assertEquals(original.subtitle.inventory.map { it.url }, rows.map { it.url })
        val other = assertIs<SubtitleIdentity.Embedded>(playbackSubtitleIdentity(rows[1]))
        assertEquals("4", other.containerTrackId)
        assertIs<SubtitleIdentity.ServerSidecar>(playbackSubtitleIdentity(rows[2]))
        // Picking it mounts nothing: the stream already carries it.
        val session = original.toSessionResponse("s1", "profile", 42)
        assertTrue(subtitlesForVideoMediaMount(rows, session.playbackPlan, other, preferMuxedTracks = true).isEmpty())
    }

    @Test fun theSelectedRowFollowsThePlansOwnDecision() {
        // The server chose a sidecar for the selected row: it stays a sidecar
        // even though the row claims native_selectable.
        val sidecar = original.copy(subtitle = original.subtitle.copy(
            embedded = null,
            artifact = PlaybackSubtitleArtifactV3("/stream/s1/subtitles/0.vtt", "text/vtt", "vtt"),
        ))
        val rows = sidecar.toSessionResponse("s1", "profile", 42).subtitleUrls!!
        assertNull(rows[0].nativeContainerTrackId)
        assertIs<SubtitleIdentity.ServerSidecar>(playbackSubtitleIdentity(rows[0]))
        assertEquals("4", rows[1].nativeContainerTrackId)
    }

    @Test fun everyTrackRenditionRowsCarryTheirRenditionAndNoUrl() {
        val rows = transcode.toSessionResponse("s1", "profile", 42).subtitleUrls!!
        assertEquals(
            listOf(SubtitleHlsRendition("subs", "Track 0", 1), SubtitleHlsRendition("subs", "Track 1", 2)),
            rows.map { it.hlsRendition },
        )
        assertEquals(listOf("", ""), rows.map { it.url })
        assertEquals(listOf(null, null), rows.map { it.nativeContainerTrackId })
        val identity = assertIs<SubtitleIdentity.Embedded>(playbackSubtitleIdentity(rows[1]))
        assertEquals("subs:Track 1", identity.hlsRendition?.media3TrackId)
        // A single-rendition plan lists no others: their sidecars stay.
        val single = transcode.copy(subtitle = transcode.subtitle.copy(inventory = listOf(row(0), row(1))))
            .toSessionResponse("s1", "profile", 42).subtitleUrls!!
        assertNull(single[1].hlsRendition)
        assertEquals("/stream/s1/subtitles/1.vtt", single[1].url)
    }

    @Test fun onlyTracksTheStreamCarriesAndOffSwitchInPlace() {
        val nativeRows = original.toSessionResponse("s1", "profile", 42).subtitleUrls!!
        val nativePlan = original.toSessionResponse("s1", "profile", 42).playbackPlan
        val committed = playbackSubtitleIdentity(nativeRows[0])
        val other = playbackSubtitleIdentity(nativeRows[1])
        assertTrue(canSwitchSubtitleInPlace(nativePlan, nativeRows, committed, other))
        assertTrue(canSwitchSubtitleInPlace(nativePlan, nativeRows, committed, SubtitleIdentity.Off))
        assertTrue(canSwitchSubtitleInPlace(nativePlan, nativeRows, SubtitleIdentity.Off, committed))
        assertFalse(canSwitchSubtitleInPlace(nativePlan, nativeRows, committed, committed))
        assertFalse(canSwitchSubtitleInPlace(nativePlan, nativeRows, committed, playbackSubtitleIdentity(nativeRows[2])))
        assertFalse(canSwitchSubtitleInPlace(null, nativeRows, committed, other))

        val hlsSession = transcode.toSessionResponse("s1", "profile", 42)
        val hlsRows = hlsSession.subtitleUrls!!
        val hlsCommitted = playbackSubtitleIdentity(hlsRows[0])
        assertTrue(canSwitchSubtitleInPlace(hlsSession.playbackPlan, hlsRows, hlsCommitted, playbackSubtitleIdentity(hlsRows[1])))
        assertTrue(canSwitchSubtitleInPlace(hlsSession.playbackPlan, hlsRows, hlsCommitted, SubtitleIdentity.Off))
        // A rendition without an index is the single rendition: no other track to switch to.
        val unindexed = SubtitleIdentity.Embedded(1, SubtitleMediaIdentity(), hlsRendition = SubtitleHlsRendition("subs", "Track 1"))
        assertFalse(canSwitchSubtitleInPlace(hlsSession.playbackPlan, hlsRows, hlsCommitted, unindexed))
        // Burn-in is part of the picture; leaving it needs a new stream.
        assertFalse(canSwitchSubtitleInPlace(hlsSession.playbackPlan, hlsRows, SubtitleIdentity.ServerBurnIn(0), SubtitleIdentity.Off))
    }

    @Test fun anInPlacePlanSharesTheMediaMountKeyAndAnythingElseDoesNot() {
        val playing = original.toSessionResponse("s1", "profile", 42).playbackPlan!!
        val switched = original.copy(
            planId = "plan-1", planAttemptKey = "v3:1",
            timeline = original.timeline.copy(sourceStartSeconds = 620.0, playerStartSeconds = 620.0),
            selectedTracks = original.selectedTracks.copy(subtitle = PlaybackTrackIdentityV3("file:42:subtitle:1", 1)),
            subtitle = original.subtitle.copy(
                trackId = "file:42:subtitle:1",
                embedded = PlaybackEmbeddedSubtitleV3(streamIndex = 3, containerTrackId = "4"),
            ),
        ).toSessionResponse("s1", "profile", 42).playbackPlan!!
        assertNotEquals(playing, switched)
        assertEquals(playing.mediaMountKey(), switched.mediaMountKey())

        val otherStream = original.copy(stream = original.stream.copy(url = "/api/v2/stream/s1?v=2"))
            .toSessionResponse("s1", "profile", 42).playbackPlan!!
        assertNotEquals(playing.mediaMountKey(), otherStream.mediaMountKey())
        val dv8 = original.copy(decisionReason = DECISION_REASON_CLIENT_DV8_BASE_LAYER)
            .toSessionResponse("s1", "profile", 42).playbackPlan!!
        assertNotEquals(playing.mediaMountKey(), dv8.mediaMountKey())
        val offset = original.copy(timeline = original.timeline.copy(timelineOffsetSeconds = 3.0))
            .toSessionResponse("s1", "profile", 42).playbackPlan!!
        assertNotEquals(playing.mediaMountKey(), offset.mediaMountKey())
    }

    @Test fun anAdoptedPlanKeepsWhatTheClientChangedOnTheMount() {
        // A local PCM retry rewrote the mounted plan's audio claim and trace.
        val mounted = original.toSessionResponse("s1", "profile", 42).playbackPlan!!.let { plan ->
            plan.copy(
                claims = plan.claims.copy(audio = plan.claims.audio.copy(passthrough = false, reason = "client_pcm_retry")),
                decisionTrace = plan.decisionTrace + "client_retry=pcm_decode:audio/eac3",
            )
        }
        val adopted = original.copy(
            planId = "plan-1",
            selectedTracks = original.selectedTracks.copy(subtitle = PlaybackTrackIdentityV3("file:42:subtitle:1", 1)),
            subtitle = original.subtitle.copy(
                trackId = "file:42:subtitle:1",
                embedded = PlaybackEmbeddedSubtitleV3(streamIndex = 3, containerTrackId = "4"),
            ),
        ).toSessionResponse("s1", "profile", 42).playbackPlan!!

        val shown = mounted.withInPlaceAdoption(adopted)

        assertEquals("plan-1", shown.planId)
        assertEquals(1, shown.selectedTracks.subtitleIndex)
        assertFalse(shown.claims.audio.passthrough)
        assertEquals(mounted.decisionTrace, shown.decisionTrace)
        assertEquals(mounted.mediaMountKey(), shown.mediaMountKey())
        assertEquals(
            mountedPlaybackKey("s1", "/api/v2/stream/s1", mounted, 7),
            mountedPlaybackKey("s1", "/api/v2/stream/s1", shown, 7),
        )
        // The raw adopted plan would have changed the mount key: that is the remount it avoids.
        assertNotEquals(mounted.mediaMountKey(), adopted.mediaMountKey())
    }

    @Test fun theStallDetectorsKeepTheirMountAcrossAnInPlacePlan() {
        val playing = original.toSessionResponse("s1", "profile", 42).playbackPlan!!
        val switched = playing.copy(planId = "plan-1")
        assertEquals(
            mountedPlaybackKey("s1", "/api/v2/stream/s1", playing, 7),
            mountedPlaybackKey("s1", "/api/v2/stream/s1", switched, 7),
        )
        assertNotEquals(
            mountedPlaybackKey("s1", "/api/v2/stream/s1", playing, 7),
            mountedPlaybackKey("s1", "/api/v2/stream/s1", playing, 8),
        )
    }

    @Test fun offIsAppliedWhenNoTextTrackIsSelected() {
        fun group(selected: Boolean) = Tracks.Group(
            TrackGroup(Format.Builder().setId("3").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected),
        )
        assertTrue(isSubtitleSelectionApplied(Tracks(listOf(group(false))), SubtitleIdentity.Off))
        assertFalse(isSubtitleSelectionApplied(Tracks(listOf(group(true))), SubtitleIdentity.Off))
        val native = SubtitleIdentity.Embedded(0, SubtitleMediaIdentity(), "3")
        assertTrue(isSubtitleSelectionApplied(Tracks(listOf(group(true))), native))
        assertFalse(isSubtitleSelectionApplied(Tracks(listOf(group(false))), native))
    }
}
