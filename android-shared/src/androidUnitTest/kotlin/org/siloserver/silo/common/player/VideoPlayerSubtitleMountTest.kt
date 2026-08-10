package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SelectedPlaybackTracks
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoPlayerSubtitleMountTest {
    @Test
    fun v3ServerSidecarMountIgnoresEveryUncommittedDownload() {
        val rows = listOf(
            serverRow(index = 0),
            serverRow(index = 7),
            serverRow(index = 17),
            PlayerSubtitleInfo(
                index = 18,
                source = "downloaded",
                downloadId = 312,
                url = "content://downloads/subtitle-312.vtt",
            ),
        )

        val mounted = subtitlesForVideoMediaMount(
            subtitles = rows,
            playbackPlan = plan(selectedSubtitleIndex = 7),
            subtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 7),
        )

        assertEquals(listOf(7), mounted.map(PlayerSubtitleInfo::index))
    }

    @Test
    fun v3DownloadedMountAttachesOnlyTheCommittedDownload() {
        val rows = listOf(
            serverRow(index = 0),
            serverRow(index = 7),
            PlayerSubtitleInfo(
                index = 8,
                source = "downloaded",
                downloadId = 44,
                url = "content://downloads/subtitle-44.vtt",
            ),
            PlayerSubtitleInfo(
                index = 9,
                source = "downloaded",
                downloadId = 45,
                url = "content://downloads/subtitle-45.vtt",
            ),
        )

        val mounted = subtitlesForVideoMediaMount(
            subtitles = rows,
            playbackPlan = plan(selectedSubtitleIndex = null),
            subtitleIdentity = SubtitleIdentity.Downloaded(
                downloadId = 44,
                media = SubtitleMediaIdentity(),
            ),
        )

        assertEquals(listOf(8), mounted.map(PlayerSubtitleInfo::index))
    }

    @Test
    fun v3OffPlanDoesNotAttachAnySubtitleArtifact() {
        val rows = listOf(serverRow(index = 0), serverRow(index = 7))

        val mounted = subtitlesForVideoMediaMount(
            subtitles = rows,
            playbackPlan = plan(selectedSubtitleIndex = null),
            subtitleIdentity = SubtitleIdentity.Off,
        )

        assertEquals(emptyList(), mounted)
    }

    @Test
    fun legacyAndOfflineMountsKeepTheirSuppliedSubtitleContract() {
        val rows = listOf(serverRow(index = 0), serverRow(index = 7))

        assertEquals(
            rows,
            subtitlesForVideoMediaMount(
                subtitles = rows,
                playbackPlan = null,
                subtitleIdentity = SubtitleIdentity.Off,
            ),
        )
    }

    private fun serverRow(index: Int): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = index,
        source = "external",
        serverTrackId = "file:482:subtitle:$index",
        serverDelivery = "sidecar",
        url = "/stream/session/subtitles/$index.vtt",
    )

    private fun plan(selectedSubtitleIndex: Int?): PlaybackExecutionPlan = PlaybackExecutionPlan(
        planId = "plan",
        delivery = PlaybackDelivery.SERVER_REMUX_HLS,
        routeFamily = PlaybackRouteFamily.SERVER_ADAPTIVE,
        selectedTracks = SelectedPlaybackTracks(subtitleIndex = selectedSubtitleIndex),
    )
}
