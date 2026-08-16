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

    // Protocol v3 types a row describing a track MUXED into the file as
    // `delivery = sidecar` too. On the untouched original that track is already
    // in the stream, so a caller that can select it in place must not have the
    // server-extracted duplicate attached (it stalls the mount and paints the
    // cue backlog past the resume point).
    @Test
    fun v3MuxedEmbeddedRowMountsNothingOnDirectPlayWhenMuxedTracksPreferred() {
        val rows = listOf(serverRow(index = 3), embeddedPgsRow(index = 8))

        val mounted = subtitlesForVideoMediaMount(
            subtitles = rows,
            playbackPlan = plan(selectedSubtitleIndex = 8, delivery = PlaybackDelivery.ORIGINAL_HTTP),
            subtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 8),
            preferMuxedTracks = true,
        )

        assertEquals(emptyList(), mounted)
    }

    @Test
    fun v3MuxedEmbeddedRowStillMountsTheSidecarWhenCallerCannotSelectInPlace() {
        val rows = listOf(serverRow(index = 3), embeddedPgsRow(index = 8))

        val mounted = subtitlesForVideoMediaMount(
            subtitles = rows,
            playbackPlan = plan(selectedSubtitleIndex = 8, delivery = PlaybackDelivery.ORIGINAL_HTTP),
            subtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 8),
        )

        assertEquals(listOf(8), mounted.map(PlayerSubtitleInfo::index))
    }

    @Test
    fun v3MuxedEmbeddedRowStillMountsTheSidecarOnRemuxAndTranscodeDeliveries() {
        val rows = listOf(serverRow(index = 3), embeddedPgsRow(index = 8))
        for (delivery in listOf(
            PlaybackDelivery.SERVER_REMUX_HLS,
            PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
            PlaybackDelivery.SERVER_TRANSCODE_HLS,
        )) {
            val mounted = subtitlesForVideoMediaMount(
                subtitles = rows,
                playbackPlan = plan(selectedSubtitleIndex = 8, delivery = delivery),
                subtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 8),
                preferMuxedTracks = true,
            )
            assertEquals(listOf(8), mounted.map(PlayerSubtitleInfo::index), "delivery=$delivery")
        }
    }

    @Test
    fun v3ExternalRowStillMountsOnDirectPlayWhenMuxedTracksPreferred() {
        val rows = listOf(serverRow(index = 3), embeddedPgsRow(index = 8))

        val mounted = subtitlesForVideoMediaMount(
            subtitles = rows,
            playbackPlan = plan(selectedSubtitleIndex = 3, delivery = PlaybackDelivery.ORIGINAL_HTTP),
            subtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 3),
            preferMuxedTracks = true,
        )

        assertEquals(listOf(3), mounted.map(PlayerSubtitleInfo::index))
    }

    @Test
    fun v3MuxedBitmapRowTheClientCannotDecodeStillMountsTheSidecar() {
        val rows = listOf(embeddedPgsRow(index = 8).copy(codec = "dvb_subtitle"))

        val mounted = subtitlesForVideoMediaMount(
            subtitles = rows,
            playbackPlan = plan(selectedSubtitleIndex = 8, delivery = PlaybackDelivery.ORIGINAL_HTTP),
            subtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 8),
            preferMuxedTracks = true,
        )

        assertEquals(listOf(8), mounted.map(PlayerSubtitleInfo::index))
    }

    private fun serverRow(index: Int): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = index,
        source = "external",
        catalogSource = "external",
        serverTrackId = "file:482:subtitle:$index",
        serverDelivery = "sidecar",
        url = "/stream/session/subtitles/$index.vtt",
    )

    /** A v3 row for a PGS track muxed into the file: typed sidecar all the same. */
    private fun embeddedPgsRow(index: Int): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = "hdmv_pgs_subtitle",
        label = "English",
        source = "embedded",
        catalogSource = "embedded",
        serverTrackId = "file:482:subtitle:$index",
        serverDelivery = "sidecar",
        url = "/stream/session/subtitles/$index.sup",
    )

    private fun plan(
        selectedSubtitleIndex: Int?,
        delivery: PlaybackDelivery = PlaybackDelivery.SERVER_REMUX_HLS,
    ): PlaybackExecutionPlan = PlaybackExecutionPlan(
        planId = "plan",
        delivery = delivery,
        routeFamily = PlaybackRouteFamily.SERVER_ADAPTIVE,
        selectedTracks = SelectedPlaybackTracks(subtitleIndex = selectedSubtitleIndex),
    )
}
