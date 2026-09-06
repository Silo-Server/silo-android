package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.video.EpisodeSubtitleMode
import org.siloserver.silo.common.player.video.ResolvedEpisodeSelection
import org.siloserver.silo.common.player.video.encodeEpisodeSelectionHandoff
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.watchtogether.shouldNavigateToLocalNext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvPlayNextSelectionHandoffTest {
    @Test
    fun serverUnreachableStartKeepsHandoffForOwnedRetry() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(fileId = 42, resolution = "1080p"),
            committedSubtitleIdentity = SubtitleIdentity.Off,
            catalogSubtitles = emptyList(),
            hasExplicitSubtitleSelection = true,
        )
        val slot = TvEpisodeSelectionHandoffSlot(handoff)

        val failedStart = requireNotNull(slot.leaseForStart(ownerGeneration = 1L))
        assertEquals(handoff, failedStart.handoff)
        assertTrue(slot.retainForRetry(failedStart))

        assertEquals(handoff, slot.leaseForStart(ownerGeneration = 2L)?.handoff)
    }

    @Test
    fun startErrorKeepsHandoffForOwnedRetry() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(fileId = 42, resolution = "2160p"),
            committedSubtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 3),
            catalogSubtitles = listOf(subtitle(index = 3, language = "nl", codec = "srt")),
            hasExplicitSubtitleSelection = true,
        )
        val slot = TvEpisodeSelectionHandoffSlot(handoff)

        val failedStart = requireNotNull(slot.leaseForStart(ownerGeneration = 7L))
        assertTrue(slot.retainForRetry(failedStart))

        assertEquals(handoff, slot.leaseForStart(ownerGeneration = 8L)?.handoff)
    }

    @Test
    fun successfulReadyAcknowledgesHandoffOnlyForItsLease() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(fileId = 42, resolution = "1080p"),
            committedSubtitleIdentity = SubtitleIdentity.Off,
            catalogSubtitles = emptyList(),
            hasExplicitSubtitleSelection = true,
        )
        val slot = TvEpisodeSelectionHandoffSlot(handoff)
        val readyStart = requireNotNull(slot.leaseForStart(ownerGeneration = 11L))

        assertTrue(slot.acknowledgeReady(readyStart))
        assertNull(slot.leaseForStart(ownerGeneration = 12L))
        assertTrue(slot.acknowledgeReady(readyStart).not(), "a Ready completion can ack only once")
    }

    @Test
    fun explicitReplacementInvalidatesHandoffAndStaleCompletionCannotRestoreIt() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(fileId = 42, resolution = "1080p"),
            committedSubtitleIdentity = SubtitleIdentity.Off,
            catalogSubtitles = emptyList(),
            hasExplicitSubtitleSelection = true,
        )
        val slot = TvEpisodeSelectionHandoffSlot(handoff)
        val staleStart = requireNotNull(slot.leaseForStart(ownerGeneration = 21L))

        slot.invalidate()

        assertTrue(slot.retainForRetry(staleStart).not())
        assertTrue(slot.acknowledgeReady(staleStart).not())
        assertNull(slot.leaseForStart(ownerGeneration = 22L))
    }

    @Test
    fun inPlaceReplacementInstallsNewHandoffAndRejectsOldLease() {
        val oldHandoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(fileId = 42, resolution = "1080p"),
            committedSubtitleIdentity = SubtitleIdentity.Off,
            catalogSubtitles = emptyList(),
            hasExplicitSubtitleSelection = true,
        )
        val newHandoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(fileId = 84, resolution = "2160p"),
            committedSubtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 3),
            catalogSubtitles = listOf(subtitle(index = 3, language = "nl", codec = "srt")),
            hasExplicitSubtitleSelection = true,
        )
        val slot = TvEpisodeSelectionHandoffSlot(oldHandoff)
        val staleLease = requireNotNull(slot.leaseForStart(ownerGeneration = 1L))

        slot.replace(newHandoff)

        assertTrue(slot.acknowledgeReady(staleLease).not())
        assertEquals(newHandoff, slot.leaseForStart(ownerGeneration = 2L)?.handoff)
    }

    @Test
    fun newerLaunchOwnerInvalidatesOlderLease() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(fileId = 42, resolution = "1080p"),
            committedSubtitleIdentity = SubtitleIdentity.Off,
            catalogSubtitles = emptyList(),
            hasExplicitSubtitleSelection = true,
        )
        val slot = TvEpisodeSelectionHandoffSlot(handoff)
        val staleStart = requireNotNull(slot.leaseForStart(ownerGeneration = 31L))

        assertNull(slot.leaseForStart(ownerGeneration = 32L))
        assertTrue(slot.retainForRetry(staleStart).not())
        assertTrue(slot.acknowledgeReady(staleStart).not())
    }

    @Test
    fun nextEpisodeCapturesCurrentSourceAndCommittedSubtitleSemantics() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(
                fileId = 42,
                resolution = "2160p",
                codecVideo = "hevc",
                container = "mkv",
            ),
            committedSubtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 9),
            catalogSubtitles = listOf(subtitle(index = 9, language = "nl", codec = "srt")),
            hasExplicitSubtitleSelection = true,
        )

        assertEquals("2160p", handoff.source?.resolution)
        assertEquals("hevc", handoff.source?.videoCodec)
        assertEquals("nl", handoff.subtitle.language)
        assertEquals("subrip", handoff.subtitle.codecFamily)
        assertTrue(handoff.toString().contains("42").not(), "episode-local file IDs must not cross episodes")
        assertTrue(handoff.toString().contains("9").not(), "episode-local subtitle indexes must not cross episodes")
    }

    @Test
    fun nextEpisodeCarriesExplicitOff() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = null,
            committedSubtitleIdentity = SubtitleIdentity.Off,
            catalogSubtitles = emptyList(),
            hasExplicitSubtitleSelection = true,
        )

        assertEquals(EpisodeSubtitleMode.OFF, handoff.subtitle.mode)
    }

    @Test
    fun nextEpisodeCarriesAutoWhenNoExplicitSubtitleWasCommitted() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = null,
            committedSubtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 9),
            catalogSubtitles = listOf(subtitle(index = 9, language = "nl", codec = "srt")),
            hasExplicitSubtitleSelection = false,
        )

        assertEquals(EpisodeSubtitleMode.AUTO, handoff.subtitle.mode)
    }

    @Test
    fun downloadedPlaybackDropsDownloadIdentityButKeepsPortableMediaSemantics() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = FileVersion(
                fileId = 42,
                fileName = "source-42.mkv",
                filePath = "/media/source-42.mkv",
                resolution = "1080p",
                codecVideo = "h264",
            ),
            committedSubtitleIdentity = SubtitleIdentity.Downloaded(
                downloadId = 777,
                media = SubtitleMediaIdentity(
                    trackId = "download-track-777",
                    language = "NL",
                    codecFamily = "srt",
                    forced = true,
                    hearingImpaired = true,
                ),
            ),
            catalogSubtitles = listOf(subtitle(index = 9, language = "nl", codec = "srt")),
            hasExplicitSubtitleSelection = true,
        )

        assertEquals("1080p", handoff.source?.resolution)
        assertEquals(EpisodeSubtitleMode.TRACK, handoff.subtitle.mode)
        assertEquals("nl", handoff.subtitle.language)
        assertEquals("subrip", handoff.subtitle.codecFamily)
        assertEquals(true, handoff.subtitle.forced)
        assertEquals(true, handoff.subtitle.hearingImpaired)
        assertEquals(true, handoff.subtitle.external)
        val encoded = encodeEpisodeSelectionHandoff(handoff)
        assertTrue(encoded.contains("777").not(), "download identity is episode-local")
        assertTrue(encoded.contains("download-track").not(), "Media3 identity is episode-local")
        assertTrue(encoded.contains("fileId").not(), "file identity is episode-local")
        assertTrue(encoded.contains("index").not(), "subtitle indexes are episode-local")
        assertTrue(encoded.contains("source-42").not(), "file name and path are episode-local")
        assertTrue(encoded.contains("example.test").not(), "subtitle URLs are episode-local")
    }

    @Test
    fun localMedia3PlaybackKeepsPortableMediaSemanticsWithoutTrackId() {
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = null,
            committedSubtitleIdentity = SubtitleIdentity.LocalMedia3(
                SubtitleMediaIdentity(
                    trackId = "media3-opaque-id",
                    language = "EN-us",
                    codecFamily = "webvtt",
                    forced = false,
                    hearingImpaired = true,
                ),
            ),
            catalogSubtitles = emptyList(),
            hasExplicitSubtitleSelection = true,
        )

        assertEquals(EpisodeSubtitleMode.TRACK, handoff.subtitle.mode)
        assertEquals("en", handoff.subtitle.language)
        assertEquals("webvtt", handoff.subtitle.codecFamily)
        assertEquals(false, handoff.subtitle.forced)
        assertEquals(true, handoff.subtitle.hearingImpaired)
        assertNull(handoff.subtitle.external)
        assertTrue(encodeEpisodeSelectionHandoff(handoff).contains("media3-opaque-id").not())
    }

    @Test
    fun watchTogetherStillSuppressesSoloAutoAdvance() {
        assertTrue(shouldNavigateToLocalNext(inWatchTogetherRoom = false))
        assertTrue(shouldNavigateToLocalNext(inWatchTogetherRoom = true).not())
    }

    @Test
    fun profileOrServerReplacementDoesNotReuseAnOldHandoff() {
        val slot = TvEpisodeSelectionHandoffSlot(
            captureTvEpisodeSelectionHandoff(
                activeVersion = FileVersion(fileId = 42, resolution = "1080p"),
                committedSubtitleIdentity = SubtitleIdentity.Off,
                catalogSubtitles = emptyList(),
                hasExplicitSubtitleSelection = true,
            ),
        )

        assertEquals(
            EpisodeSubtitleMode.OFF,
            slot.leaseForStart(ownerGeneration = 1L)?.handoff?.subtitle?.mode,
        )
        slot.invalidate()
        assertNull(
            slot.leaseForStart(ownerGeneration = 2L),
            "profile/server replacement starts must not reuse a prior episode handoff",
        )
    }

    @Test
    fun resolvedExplicitTargetSubtitleBlocksDurableTargetRestore() {
        val application = resolveTvEpisodeInitialSubtitleSelection(
            episodeSelectionHandoff = captureTvEpisodeSelectionHandoff(
                activeVersion = null,
                committedSubtitleIdentity = SubtitleIdentity.Off,
                catalogSubtitles = emptyList(),
                hasExplicitSubtitleSelection = true,
            ),
            resolvedEpisodeSelection = ResolvedEpisodeSelection(
                fileId = 1080,
                subtitleTrackIndex = null,
                subtitleIntentSpecified = true,
            ),
            existingPendingInitialSubtitleIndex = 4,
        )

        assertNull(application.pendingInitialSubtitleIndex)
        assertTrue(application.suppressDurableSubtitleRestore)
    }

    @Test
    fun resolvedOffAppliesMedia3OffAndAutoPreservesDurableRestore() {
        val off = resolveTvEpisodeInitialSubtitleSelection(
            episodeSelectionHandoff = captureTvEpisodeSelectionHandoff(
                activeVersion = null,
                committedSubtitleIdentity = SubtitleIdentity.Off,
                catalogSubtitles = emptyList(),
                hasExplicitSubtitleSelection = true,
            ),
            resolvedEpisodeSelection = ResolvedEpisodeSelection(
                fileId = null,
                subtitleTrackIndex = -1,
                subtitleIntentSpecified = true,
            ),
            existingPendingInitialSubtitleIndex = null,
        )
        val automatic = resolveTvEpisodeInitialSubtitleSelection(
            episodeSelectionHandoff = captureTvEpisodeSelectionHandoff(
                activeVersion = null,
                committedSubtitleIdentity = SubtitleIdentity.Off,
                catalogSubtitles = emptyList(),
                hasExplicitSubtitleSelection = false,
            ),
            resolvedEpisodeSelection = ResolvedEpisodeSelection(
                fileId = null,
                subtitleTrackIndex = null,
                subtitleIntentSpecified = false,
            ),
            existingPendingInitialSubtitleIndex = null,
        )

        assertEquals(-1, off.pendingInitialSubtitleIndex)
        assertTrue(off.suppressDurableSubtitleRestore)
        assertTrue(automatic.suppressDurableSubtitleRestore.not())
    }

    private fun subtitle(index: Int, language: String, codec: String) = PlayerSubtitleInfo(
        index = index,
        language = language,
        codec = codec,
        url = "https://example.test/$index.$codec",
    )
}
