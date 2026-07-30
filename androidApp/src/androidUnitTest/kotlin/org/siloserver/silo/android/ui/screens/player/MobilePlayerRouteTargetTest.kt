package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.playback.decodeSubtitleIdentityPreference
import org.siloserver.silo.playback.encodeCatalogSubtitlePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobilePlayerRouteTargetTest {
    @Test
    fun readyTargetUsesLiveContentFileAudioAndCatalogSubtitleOrdinal() {
        val catalogSubtitles = listOf(
            SubtitleTrack(index = 10, language = "en", title = "English", external = true),
            SubtitleTrack(index = 20, language = "fr", title = "French", external = true),
        )
        val version = FileVersion(
            fileId = 222,
            audioTracks = listOf(
                AudioTrack(index = 3, language = "en"),
                AudioTrack(index = 7, language = "fr"),
            ),
            subtitleTracks = catalogSubtitles,
        )
        val state = PlayerViewModel.PlayerUiState(
            isLoading = false,
            contentId = "episode-2",
            streamUrl = "https://example.test/video.m3u8",
            versions = listOf(version),
            selectedVersionIndex = 0,
            audioTracks = version.audioTracks.orEmpty(),
            selectedAudioIndex = 1,
            // Mounted order differs from the catalog order. Route values are
            // catalog ordinals, so selectedSubtitleIndex must not be compared.
            subtitleTracks = listOf(
                PlayerSubtitleInfo(index = 1, language = "fr", label = "French", url = "fr.vtt"),
                PlayerSubtitleInfo(index = 0, language = "en", label = "English", url = "en.vtt"),
            ),
            selectedSubtitleIndex = 1,
            committedSubtitleIdentity = SubtitleIdentity.ServerSidecar(serverIndex = 0),
            position = 95.0,
        )

        val target = requireNotNull(
            mobilePlayerRouteTarget(
                intent = MobilePlayerRouteIntent(
                    contentId = "episode-2",
                    resumePositionSeconds = 42.0,
                ),
                state = state,
            ),
        )

        assertEquals("episode-2", target.contentId)
        assertEquals(222, target.fileId)
        assertEquals(1, target.audioTrackIndex)
        assertEquals(0, target.subtitleTrackIndex)
        assertEquals(42.0, target.resumePositionSeconds)
    }

    @Test
    fun pendingLoadUsesNewIntentInsteadOfStaleMountedState() {
        val intent = MobilePlayerRouteIntent(
            contentId = "episode-2",
            fileId = 222,
            quality = "720p",
            audioTrackIndex = 1,
            subtitleTrackIndex = -1,
            resumePositionSeconds = 42.0,
        )
        val target = requireNotNull(
            mobilePlayerRouteTarget(
                intent = intent,
                state = PlayerViewModel.PlayerUiState(
                    isLoading = true,
                    contentId = "episode-2",
                    streamUrl = "https://example.test/stale-video.m3u8",
                    versions = listOf(FileVersion(fileId = 111)),
                ),
            ),
        )

        assertEquals(222, target.fileId)
        assertEquals("720p", target.quality)
        assertEquals(1, target.audioTrackIndex)
        assertEquals(-1, target.subtitleTrackIndex)
        assertEquals(42.0, target.resumePositionSeconds)
    }

    @Test
    fun catalogSubtitleOrdinalUsesStableIdentityAfterCatalogReorder() {
        val originalCatalog = listOf(
            SubtitleTrack(
                index = 10,
                language = "en",
                title = "English",
                codec = "srt",
                external = true,
            ),
            SubtitleTrack(
                index = 20,
                language = "fr",
                title = "French",
                codec = "srt",
                external = true,
            ),
        )
        val french = requireNotNull(
            decodeSubtitleIdentityPreference(
                encodeCatalogSubtitlePreference(originalCatalog, selectedOrdinal = 1),
            ),
        )
        val state = PlayerViewModel.PlayerUiState(
            versions = listOf(
                FileVersion(
                    fileId = 222,
                    subtitleTracks = listOf(
                        SubtitleTrack(
                            index = 20,
                            language = "fr",
                            title = "French",
                            codec = "srt",
                            external = true,
                        ),
                        SubtitleTrack(
                            index = 10,
                            language = "en",
                            title = "English",
                            codec = "srt",
                            external = true,
                        ),
                    ),
                ),
            ),
            selectedVersionIndex = 0,
        )

        assertEquals(0, catalogSubtitleRouteOrdinal(state, french))
        assertEquals(-1, catalogSubtitleRouteOrdinal(state, SubtitleIdentity.Off))
        assertNull(
            catalogSubtitleRouteOrdinal(
                state,
                SubtitleIdentity.Downloaded(
                    downloadId = 99,
                    media = SubtitleMediaIdentity(label = "French", language = "fr"),
                ),
            ),
        )
    }

    @Test
    fun versionSelectionChangesOnlyFileIntentAndRecoveryRestoresPriorIntent() {
        val state = MobilePlayerRouteIntentState()
        state.beginLoad(
            contentId = "movie-1",
            fileId = null,
            quality = null,
            audioTrackIndex = null,
            subtitleTrackIndex = null,
            resumePositionSeconds = 42.0,
            preserveCurrent = false,
        )
        val initial = requireNotNull(state.current)

        state.beginVersionSelection(contentId = "movie-1", fileId = 222)

        val switched = requireNotNull(state.current)
        assertEquals(222, switched.fileId)
        assertTrue(switched.fileIsExplicit)
        assertFalse(switched.qualityIsExplicit)
        assertFalse(switched.audioTrackIsExplicit)
        assertFalse(switched.subtitleTrackIsExplicit)
        assertEquals(42.0, switched.resumePositionSeconds)

        state.recoverVersionSelection(contentId = "movie-1")
        assertEquals(initial, state.current)
    }

    @Test
    fun preservedExplicitQualityIsReappliedToTheActualLoad() {
        val state = MobilePlayerRouteIntentState()
        state.beginLoad(
            contentId = "movie-1",
            fileId = null,
            quality = "720p",
            audioTrackIndex = null,
            subtitleTrackIndex = null,
            resumePositionSeconds = null,
            preserveCurrent = false,
        )
        state.beginVersionSelection(contentId = "movie-1", fileId = 222)
        state.beginLoad(
            contentId = "movie-1",
            fileId = 222,
            quality = null,
            audioTrackIndex = 1,
            subtitleTrackIndex = -1,
            resumePositionSeconds = null,
            preserveCurrent = true,
        )

        assertEquals(
            "720p",
            state.qualityForLoad(
                contentId = "movie-1",
                normalizedRequestedQuality = null,
                preserveCurrent = true,
            ),
        )
    }

    @Test
    fun internalTrackRestoreStaysAutomaticAndUserIntentCommitsOnlyOnSuccess() {
        val state = MobilePlayerRouteIntentState()
        state.beginLoad(
            contentId = "movie-1",
            fileId = null,
            quality = null,
            audioTrackIndex = null,
            subtitleTrackIndex = null,
            resumePositionSeconds = null,
            preserveCurrent = false,
        )
        val french = SubtitleIdentity.ServerSidecar(serverIndex = 20)

        // A persisted/automatic restore has no staged user provenance.
        state.applyCommittedTracks(
            contentId = "movie-1",
            committedAudioServerIndex = 7,
            committedSubtitleIdentity = french,
            transactionFailed = false,
            transactionActive = false,
        )
        assertFalse(requireNotNull(state.current).audioTrackIsExplicit)
        assertFalse(requireNotNull(state.current).subtitleTrackIsExplicit)

        state.beginAudioSelection(contentId = "movie-1", routeOrdinal = 1, serverIndex = 7)
        state.beginSubtitleSelection(contentId = "movie-1", routeOrdinal = 0, identity = french)
        state.applyCommittedTracks(
            contentId = "movie-1",
            committedAudioServerIndex = 3,
            committedSubtitleIdentity = SubtitleIdentity.Off,
            transactionFailed = false,
            transactionActive = true,
        )
        assertFalse(requireNotNull(state.current).audioTrackIsExplicit)
        assertFalse(requireNotNull(state.current).subtitleTrackIsExplicit)

        state.applyCommittedTracks(
            contentId = "movie-1",
            committedAudioServerIndex = 3,
            committedSubtitleIdentity = SubtitleIdentity.Off,
            transactionFailed = true,
            transactionActive = false,
        )
        assertFalse(requireNotNull(state.current).audioTrackIsExplicit)
        assertFalse(requireNotNull(state.current).subtitleTrackIsExplicit)

        state.beginAudioSelection(contentId = "movie-1", routeOrdinal = 1, serverIndex = 7)
        state.beginSubtitleSelection(contentId = "movie-1", routeOrdinal = 0, identity = french)
        state.applyCommittedTracks(
            contentId = "movie-1",
            committedAudioServerIndex = 7,
            committedSubtitleIdentity = french,
            transactionFailed = false,
            transactionActive = false,
        )
        val committed = requireNotNull(state.current)
        assertEquals(1, committed.audioTrackIndex)
        assertTrue(committed.audioTrackIsExplicit)
        assertEquals(0, committed.subtitleTrackIndex)
        assertTrue(committed.subtitleTrackIsExplicit)
    }

    @Test
    fun operationalRestartPositionDoesNotBecomeFreshRouteResumeIntent() {
        val state = MobilePlayerRouteIntentState()
        state.beginLoad(
            contentId = "episode-1",
            fileId = null,
            quality = null,
            audioTrackIndex = null,
            subtitleTrackIndex = null,
            resumePositionSeconds = 42.0,
            preserveCurrent = false,
        )
        state.beginLoad(
            contentId = "episode-1",
            fileId = 111,
            quality = null,
            audioTrackIndex = 0,
            subtitleTrackIndex = -1,
            resumePositionSeconds = null,
            preserveCurrent = true,
        )
        assertEquals(42.0, requireNotNull(state.current).resumePositionSeconds)

        // Auto-advance can operationally start episode 2 at 0 while its route
        // remains bare because it supplies no route resume provenance.
        state.beginLoad(
            contentId = "episode-2",
            fileId = null,
            quality = null,
            audioTrackIndex = null,
            subtitleTrackIndex = null,
            resumePositionSeconds = null,
            preserveCurrent = false,
        )
        assertNull(requireNotNull(state.current).resumePositionSeconds)
    }

    @Test
    fun failedOrExitedPlayerDoesNotClaimAnExternalTarget() {
        val intent = MobilePlayerRouteIntent(contentId = "movie-1")

        assertNull(
            mobilePlayerRouteTarget(
                intent,
                PlayerViewModel.PlayerUiState(
                    isLoading = false,
                    error = "Playback failed",
                    contentId = "movie-1",
                ),
            ),
        )
        assertNull(
            mobilePlayerRouteTarget(
                intent,
                PlayerViewModel.PlayerUiState(
                    isLoading = false,
                    contentId = "movie-1",
                    streamUrl = null,
                ),
            ),
        )
    }
}
