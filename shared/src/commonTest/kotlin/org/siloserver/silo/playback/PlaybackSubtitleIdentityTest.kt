package org.siloserver.silo.playback

import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSubtitleIdentityTest {
    @Test
    fun serverIdentityWinsOverLocalDownloadMetadata() {
        val identity = playbackSubtitleIdentity(
            downloadedRow(
                downloadId = 91,
                serverTrackId = "file:22:subtitle:4",
                serverDelivery = "sidecar",
            ),
        )

        val sidecar = assertIs<SubtitleIdentity.ServerSidecar>(identity)
        assertEquals("file:22:subtitle:4", sidecar.media?.trackId)
    }

    @Test
    fun legacyDownloadedIdentityResolvesByUniquePositiveMetadata() {
        val identity = SubtitleIdentity.Downloaded(
            downloadId = 91,
            media = SubtitleMediaIdentity(
                trackId = "silo-downloaded-subtitle:91",
                label = "Downloaded English",
                language = "eng",
                codecFamily = "vtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val rows = listOf(
            downloadedRow(
                serverTrackId = "file:22:subtitle:4",
                serverDelivery = "sidecar",
            ),
        )

        assertEquals(0, resolveDownloadedSubtitlePreferenceOrdinal(identity, rows))
    }

    @Test
    fun legacySyntheticIdAndNegativeBooleansAreNotEnoughToSelect() {
        val identity = SubtitleIdentity.Downloaded(
            downloadId = 91,
            media = SubtitleMediaIdentity(
                trackId = "silo-downloaded-subtitle:91",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val rows = listOf(
            downloadedRow(
                serverTrackId = "file:22:subtitle:4",
                serverDelivery = "sidecar",
            ),
        )

        assertNull(resolveDownloadedSubtitlePreferenceOrdinal(identity, rows))
    }

    @Test
    fun sharedSubtitleMetadataHelpersStayCanonical() {
        assertEquals("silo-downloaded-subtitle:91", downloadedSubtitleArtifactTrackId(91))
        assertTrue(subtitleLabelIndicatesHearingImpaired("English SDH"))
        assertTrue(subtitleLabelIndicatesHearingImpaired("English CC"))
        assertFalse(subtitleLabelIndicatesHearingImpaired("hi"))
        assertFalse(subtitleLabelIndicatesHearingImpaired("EN - HI"))
        assertFalse(subtitleLabelIndicatesHearingImpaired("English"))
        assertTrue(isBitmapSubtitleCodecFamily("application/pgs"))
        assertFalse(isBitmapSubtitleCodecFamily("text/vtt"))
    }

    private fun downloadedRow(
        downloadId: Int? = null,
        serverTrackId: String? = null,
        serverDelivery: String? = null,
    ): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = 4,
        language = "en",
        codec = "webvtt",
        label = "Downloaded English",
        source = "downloaded",
        forced = false,
        url = "/stream/s1/subtitles/4.vtt",
        downloadId = downloadId,
        serverTrackId = serverTrackId,
        serverDelivery = serverDelivery,
    )
}
