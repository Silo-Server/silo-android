package org.siloserver.silo.android.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.subtitles.DownloadedSubtitle
import org.siloserver.silo.model.subtitles.DownloadedSubtitlesResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.playback.subtitleTrackFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class MobileFreshSubtitleRestoreTest {
    @Test
    fun authoritativeV3InventoryIsNotRebuiltFromTheDownloadedCatalog() = runTest {
        val authoritative = PlayerSubtitleInfo(
            index = 3,
            language = "es",
            codec = "ass",
            label = "Spanish",
            source = "downloaded",
            url = "/stream/fresh-session/subtitles/3.ass",
            serverTrackId = "file:7:subtitle:3",
            serverDelivery = "sidecar",
        )
        var catalogRead = false

        val result = prepareMobileFreshSubtitleRestore(
            mediaFileId = 7,
            mountedSubtitles = listOf(authoritative),
            sessionId = "fresh-session",
            serverUrl = "https://silo.test",
            persistedPreference = null,
            authoritativeInventory = true,
            loadDownloadedSubtitles = {
                catalogRead = true
                ApiResult.Success(DownloadedSubtitlesResponse(listOf(downloadedTrack(312))))
            },
        )

        assertEquals(false, catalogRead)
        assertEquals(listOf(authoritative), result.subtitleTracks)
    }

    @Test
    fun `fresh playback hydrates downloads before resolving typed download id`() = runTest {
        val result = prepareMobileFreshSubtitleRestore(
            mediaFileId = 7,
            mountedSubtitles = listOf(serverTrack()),
            sessionId = "fresh-session",
            serverUrl = "https://silo.test",
            persistedPreference = encodeSubtitleIdentityPreference(
                SubtitleIdentity.Downloaded(
                    downloadId = 312,
                    media = SubtitleMediaIdentity(
                        trackId = "silo-downloaded-subtitle:312",
                    ),
                ),
            ),
            loadDownloadedSubtitles = {
                ApiResult.Success(DownloadedSubtitlesResponse(listOf(downloadedTrack(312))))
            },
        )

        assertEquals(listOf(null, 312), result.subtitleTracks.map(PlayerSubtitleInfo::downloadId))
        assertEquals(1, result.persistedSelectionOrdinal)
        assertEquals(
            312,
            assertIs<SubtitleIdentity.Downloaded>(result.persistedSelectionIdentity).downloadId,
        )
    }

    @Test
    fun `legacy local fingerprint restores as authoritative downloaded identity`() = runTest {
        val oldMountedDownload = PlayerSubtitleInfo(
            index = 1,
            language = "en",
            codec = "srt",
            label = "Release 417 (opensubtitles)",
            source = "downloaded",
            url = "/stream/old-session/subtitles/1.vtt",
            downloadId = 417,
        )

        val result = prepareMobileFreshSubtitleRestore(
            mediaFileId = 7,
            mountedSubtitles = listOf(serverTrack()),
            sessionId = "fresh-session",
            serverUrl = "https://silo.test",
            persistedPreference = subtitleTrackFingerprint(oldMountedDownload),
            loadDownloadedSubtitles = {
                ApiResult.Success(DownloadedSubtitlesResponse(listOf(downloadedTrack(417))))
            },
        )

        assertEquals(1, result.persistedSelectionOrdinal)
        assertEquals(
            417,
            assertIs<SubtitleIdentity.Downloaded>(result.persistedSelectionIdentity).downloadId,
        )
    }

    @Test
    fun `download hydration failure preserves committed server selection`() = runTest {
        val committedServerTrack = serverTrack()
        val result = prepareMobileFreshSubtitleRestore(
            mediaFileId = 7,
            mountedSubtitles = listOf(committedServerTrack),
            sessionId = "fresh-session",
            serverUrl = "https://silo.test",
            persistedPreference = encodeSubtitleIdentityPreference(
                SubtitleIdentity.Downloaded(
                    downloadId = 512,
                    media = SubtitleMediaIdentity(
                        trackId = "silo-downloaded-subtitle:512",
                    ),
                ),
            ),
            loadDownloadedSubtitles = {
                ApiResult.NetworkError(IllegalStateException("offline"))
            },
        )

        assertEquals(listOf(committedServerTrack), result.subtitleTracks)
        assertNull(result.persistedSelectionOrdinal)
        assertNull(result.persistedSelectionIdentity)
        assertEquals(true, result.persistedPreferencePresent)
    }

    @Test
    fun `cancelled download hydration cannot continue into restore`() = runTest {
        assertFailsWith<CancellationException> {
            prepareMobileFreshSubtitleRestore(
                mediaFileId = 7,
                mountedSubtitles = listOf(serverTrack()),
                sessionId = "stale-session",
                serverUrl = "https://silo.test",
                persistedPreference = null,
                loadDownloadedSubtitles = {
                    throw CancellationException("newer load owns the player")
                },
            )
        }
    }

    private fun serverTrack() = PlayerSubtitleInfo(
        index = 0,
        language = "fr",
        codec = "srt",
        label = "Server French",
        source = "external",
        url = "/stream/fresh-session/subtitles/0.vtt",
    )

    private fun downloadedTrack(id: Int) = DownloadedSubtitle(
        id = id,
        mediaFileId = 7,
        provider = "opensubtitles",
        language = "en",
        format = "srt",
        releaseName = "Release $id",
    )
}
