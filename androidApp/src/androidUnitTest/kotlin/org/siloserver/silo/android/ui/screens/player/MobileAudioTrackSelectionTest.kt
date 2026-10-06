package org.siloserver.silo.android.ui.screens.player

import kotlinx.serialization.json.Json
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.resolveAudioTrackOrdinal
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Audio is addressed by ORDINAL into `audio_tracks`. The wire carries no index
 * for audio tracks — only subtitles get one — so [AudioTrack.index] is its `0`
 * default on every row, and the tracks below deliberately leave it unset to
 * match what the server actually sends.
 */
class MobileAudioTrackSelectionTest {
    private val tracks = listOf(
        AudioTrack(language = "eng"),
        AudioTrack(language = "fra"),
    )

    @Test
    fun `picker ordinal is what the server is asked for`() {
        // This used to map through AudioTrack.index and evaluate to 0 for every
        // row, so choosing French requested English.
        assertEquals(0, selectedServerAudioTrackIndex(selectedOrdinal = 0, audioTracks = tracks))
        assertEquals(1, selectedServerAudioTrackIndex(selectedOrdinal = 1, audioTracks = tracks))
    }

    @Test
    fun `an ordinal outside the catalog is not a usable request`() {
        assertNull(selectedServerAudioTrackIndex(selectedOrdinal = 5, audioTracks = tracks))
        assertNull(selectedServerAudioTrackIndex(selectedOrdinal = 0, audioTracks = emptyList()))
    }

    @Test
    fun `the server value maps back to the same picker row`() {
        assertEquals(1, selectedAudioTrackOrdinal(selectedServerIndex = 1, audioTracks = tracks))
        assertEquals(0, selectedAudioTrackOrdinal(selectedServerIndex = 0, audioTracks = tracks))
    }

    @Test
    fun `an out of range server value falls back to the first row`() {
        assertEquals(0, selectedAudioTrackOrdinal(selectedServerIndex = 9, audioTracks = tracks))
    }

    /**
     * The committed value is an ordinal. Resolving it against AudioTrack.index
     * matched nothing above row zero, so a committed choice was silently never
     * written and reopening the item lost it.
     */
    @Test
    fun `a committed ordinal persists that row's fingerprint`() {
        val update = mobileAudioTrackPersistenceUpdate(
            committedAudioTrackIndex = 1,
            audioTracks = tracks,
        )

        assertEquals(
            TrackSelectionFingerprintUpdate.Set(audioTrackFingerprint(tracks[1])),
            update,
        )
    }

    @Test
    fun `an original download choice restores against the online catalog`() {
        val catalog = Json.decodeFromString<List<AudioTrack>>(
            """[{"language":"eng","codec":"aac","title":"English"},{"language":"deu","codec":"aac","title":"German"}]""",
        )
        val manifestTracks = catalog.mapIndexed { ordinal, track -> track.copy(index = ordinal) }
        val update = assertIs<TrackSelectionFingerprintUpdate.Set>(mobileAudioTrackPersistenceUpdate(
            committedAudioTrackIndex = 1,
            audioTracks = manifestTracks,
            originalDownload = true,
        ))

        assertEquals(1, resolveAudioTrackOrdinal(catalog, update.fingerprint))
        assertEquals(1, selectedServerAudioTrackIndex(1, manifestTracks))
    }

    @Test
    fun `no committed audio preserves whatever was stored`() {
        assertEquals(
            TrackSelectionFingerprintUpdate.Preserve,
            mobileAudioTrackPersistenceUpdate(committedAudioTrackIndex = null, audioTracks = tracks),
        )
        assertEquals(
            TrackSelectionFingerprintUpdate.Preserve,
            mobileAudioTrackPersistenceUpdate(committedAudioTrackIndex = 9, audioTracks = tracks),
        )
    }
}
