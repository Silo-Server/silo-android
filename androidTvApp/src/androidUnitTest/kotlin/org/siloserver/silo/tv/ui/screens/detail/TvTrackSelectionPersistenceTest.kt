package org.siloserver.silo.tv.ui.screens.detail

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.repository.port.LocalTrackSelection
import org.siloserver.silo.repository.port.OutboxHandle
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.repository.port.WriteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvTrackSelectionPersistenceTest {
    @Test
    fun autoClearedOnThePageOutlivesItsWriteInTheAudioPreview() {
        // The details still answer German (1); Auto was chosen here and its
        // DELETE has landed, so nothing is pending any more.
        val version = FileVersion(
            fileId = 7,
            audioTracks = listOf(AudioTrack(language = "eng", codec = "aac"), AudioTrack(language = "deu", codec = "aac")),
            effectiveAudioTrackIndex = 1,
        )
        val detail = org.siloserver.silo.model.catalog.ItemDetail(
            contentId = "e1", type = "episode", title = "E1", seriesId = "show", versions = listOf(version),
        )
        val before = TvItemDetailUiState()
        assertEquals(1, before.automaticAudioOrdinal(detail, version))

        val cleared = before.copy(
            pickedSeriesAudioChoices = mapOf("show" to org.siloserver.silo.model.playback.PendingSeriesAudioChoice(null)),
        )
        // No capabilities known in this state, so no device-language answer either.
        assertNull(cleared.automaticAudioOrdinal(detail, version))
    }

    @Test
    fun aRememberedSessionTrackYieldsToTheNewerSeriesChoice() {
        // Episode tracks: English (0), Japanese (1); subtitles: English (0), English SDH (1).
        val version = FileVersion(
            fileId = 4,
            audioTracks = listOf(AudioTrack(language = "eng", codec = "eac3"), AudioTrack(language = "jpn", codec = "ac3")),
            subtitleTracks = listOf(
                SubtitleTrack(index = 2, language = "eng", codec = "subrip", title = "English"),
                SubtitleTrack(index = 3, language = "eng", codec = "subrip", title = "SDH"),
            ),
            effectiveAudioTrackIndex = 1,
        )
        val sdh = org.siloserver.silo.model.playback.SubtitleTrackSignature(
            source = "embedded", language = "en", codec = "subrip", label = "SDH", hearingImpaired = true,
        )
        val detail = org.siloserver.silo.model.catalog.ItemDetail(
            contentId = "e04", type = "episode", title = "E4", seriesId = "show",
            versions = listOf(version), effectiveSubtitleMode = "always", effectiveSubtitleTrackSignature = sdh,
        )

        // The last playback ended on English / Off; the series now holds Japanese + SDH.
        assertEquals(null to null, sessionTracksConsistentWithSeriesChoice(detail, version, audio = 0, subtitle = -1))
        // Values that agree with the series choice stay.
        assertEquals(1 to 1, sessionTracksConsistentWithSeriesChoice(detail, version, audio = 1, subtitle = 1))
        // No series subtitle choice: the session value stands.
        val noChoice = detail.copy(effectiveSubtitleTrackSignature = null)
        assertEquals(1 to 0, sessionTracksConsistentWithSeriesChoice(noChoice, version, audio = 1, subtitle = 0))
        // Off for the series: only Off agrees.
        val off = detail.copy(effectiveSubtitleMode = "off", effectiveSubtitleTrackSignature = null)
        assertEquals(1 to null, sessionTracksConsistentWithSeriesChoice(off, version, audio = 1, subtitle = 0))
    }

    @Test
    fun nextUpSelectionRecordsEpisodeFileAndCombinedSubtitleFingerprint() = runTest {
        val version = version()
        val payload = buildTrackSelectionPersistence(
            targetContentId = "episode-42",
            version = version,
            selectedAudioIndex = 1,
            selectedSubtitleIndex = 0,
        )
        val port = RecordingTrackSelectionPort()

        recordTrackSelection(port, payload)

        assertEquals("episode-42", port.contentId)
        assertEquals(22, port.fileId)
        assertEquals(audioTrackFingerprint(version.audioTracks!![1]), port.audioFingerprint)
        // Combined index 0 is the external catalog track at ordinal 1.
        assertEquals(subtitleTrackFingerprint(version.subtitleTracks!![1]), port.subtitleFingerprint)
    }

    @Test
    fun durableFingerprintsRestoreToAudioOrdinalAndCombinedSubtitleIndex() {
        val version = version()
        val restored = restoreTrackSelection(
            version,
            LocalTrackSelection(
                audioFingerprint = audioTrackFingerprint(version.audioTracks!![1]),
                subtitleFingerprint = subtitleTrackFingerprint(version.subtitleTracks!![1]),
            ),
        )

        assertEquals(1, restored.audioIndex)
        assertEquals(0, restored.subtitleIndex)
    }

    @Test
    fun nextUpSessionIsKeyedToEpisodeIdentity() {
        TvDetailTrackSelectionSession.remember("episode-session-a", 22, 1, 0)
        TvDetailTrackSelectionSession.remember("episode-session-b", 23, null, -1)

        assertEquals(TvDetailTrackSelectionSession.Saved(22, 1, 0), TvDetailTrackSelectionSession.recall("episode-session-a"))
        assertEquals(TvDetailTrackSelectionSession.Saved(23, null, -1), TvDetailTrackSelectionSession.recall("episode-session-b"))
    }

    @Test
    fun playbackReturnPreservesPreviouslySelectedAudio() {
        val contentId = "episode-playback-return-audio"
        TvDetailTrackSelectionSession.remember(contentId, fileId = 22, audio = 1, subtitle = 0)

        TvDetailTrackSelectionSession.rememberPlaybackReturn(
            contentId = contentId,
            fileId = 22,
            audio = null,
            subtitle = 2,
            positionSeconds = 37.0,
            durationSeconds = 120.0,
        )

        assertEquals(1, TvDetailTrackSelectionSession.recall(contentId)?.audio)
    }

    @Test
    fun playbackReturnPreservesPreviouslySelectedSubtitleForKeepCurrent() {
        val contentId = "episode-playback-return-subtitle"
        TvDetailTrackSelectionSession.remember(contentId, fileId = 22, audio = 1, subtitle = 2)

        TvDetailTrackSelectionSession.rememberPlaybackReturn(
            contentId = contentId,
            fileId = 22,
            audio = null,
            subtitle = null,
            positionSeconds = 37.0,
            durationSeconds = 120.0,
        )

        assertEquals(2, TvDetailTrackSelectionSession.recall(contentId)?.subtitle)
    }

    @Test
    fun playbackReturnPreservesPreviouslySelectedFileForUnknownExitFile() {
        val contentId = "episode-playback-return-file"
        TvDetailTrackSelectionSession.remember(contentId, fileId = 22, audio = 1, subtitle = 2)

        TvDetailTrackSelectionSession.rememberPlaybackReturn(
            contentId = contentId,
            fileId = null,
            audio = null,
            subtitle = null,
            positionSeconds = 37.0,
            durationSeconds = 120.0,
        )

        assertEquals(22, TvDetailTrackSelectionSession.recall(contentId)?.fileId)
    }

    @Test
    fun playbackReturnProgressIsConsumedOnceWhileTrackChoicesRemain() {
        val contentId = "episode-playback-return-progress"
        TvDetailTrackSelectionSession.remember(contentId, fileId = 22, audio = 1, subtitle = 2)
        TvDetailTrackSelectionSession.rememberPlaybackReturn(
            contentId = contentId,
            fileId = 22,
            audio = null,
            subtitle = 2,
            positionSeconds = 37.0,
            durationSeconds = 120.0,
        )

        val playbackReturn = TvDetailTrackSelectionSession.consumePlaybackReturn(contentId)

        assertEquals(37.0, playbackReturn?.positionSeconds)
        assertNull(TvDetailTrackSelectionSession.consumePlaybackReturn(contentId))
        assertEquals(
            TvDetailTrackSelectionSession.Saved(fileId = 22, audio = 1, subtitle = 2),
            TvDetailTrackSelectionSession.recall(contentId),
        )
    }

    @Test
    fun lateRestoreCannotApplyAfterEpisodeOrVersionChanges() {
        assertTrue(shouldApplyNextUpTrackRestore("episode-42", "episode-42", 22, 22))
        assertFalse(shouldApplyNextUpTrackRestore("episode-43", "episode-42", 22, 22))
        assertFalse(shouldApplyNextUpTrackRestore("episode-42", "episode-42", 23, 22))
    }

    @Test
    fun sessionSelectedFileMergesItsDurableTracksOnReentry() {
        val version = version()
        val session = TvDetailTrackSelectionSession.Saved(fileId = 22, audio = null, subtitle = null)
        val durable = restoreTrackSelection(
            version,
            LocalTrackSelection(
                audioFingerprint = audioTrackFingerprint(version.audioTracks!![1]),
                subtitleFingerprint = subtitleTrackFingerprint(version.subtitleTracks!![1]),
            ),
        )

        val merged = mergeTrackSelection(session.audio, session.subtitle, durable)

        assertEquals(1, merged.audioIndex)
        assertEquals(0, merged.subtitleIndex)
    }

    @Test
    fun explicitSubtitleOffWinsDurableSubtitleWhileAudioStillRestores() {
        val merged = mergeTrackSelection(
            currentAudioIndex = null,
            currentSubtitleIndex = -1,
            durable = TvRestoredTrackSelection(audioIndex = 1, subtitleIndex = 0),
        )

        assertEquals(1, merged.audioIndex)
        assertEquals(-1, merged.subtitleIndex)
    }

    private fun version() = FileVersion(
        fileId = 22,
        audioTracks = listOf(
            AudioTrack(index = 4, codec = "aac", language = "eng"),
            AudioTrack(index = 9, codec = "ac3", language = "jpn", title = "Commentary"),
        ),
        subtitleTracks = listOf(
            SubtitleTrack(index = 10, codec = "srt", language = "eng"),
            SubtitleTrack(index = 0, codec = "srt", language = "fre", external = true),
            SubtitleTrack(index = 11, codec = "ass", language = "jpn"),
        ),
    )
}

private class RecordingTrackSelectionPort : UserItemStatePort {
    var contentId: String? = null
    var fileId: Int? = null
    var audioFingerprint: String? = null
    var subtitleFingerprint: String? = null

    override suspend fun recordWatched(contentId: String, watched: Boolean) = OutboxHandle.NONE
    override suspend fun recordFavorite(contentId: String, favorite: Boolean) = OutboxHandle.NONE
    override suspend fun recordRating(contentId: String, rating: Int?) = OutboxHandle.NONE
    override suspend fun resolve(handle: OutboxHandle, outcome: WriteOutcome) = Unit

    override suspend fun recordAudioTrackSelection(
        contentId: String,
        fileId: Int,
        audioFingerprint: String?,
    ) {
        this.contentId = contentId
        this.fileId = fileId
        this.audioFingerprint = audioFingerprint
    }

    override suspend fun recordSubtitleTrackSelection(
        contentId: String,
        fileId: Int,
        subtitleFingerprint: String?,
    ) {
        this.contentId = contentId
        this.fileId = fileId
        this.subtitleFingerprint = subtitleFingerprint
    }
}
