package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.catalog.AudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class MobileAudioTrackSelectionTest {
    private val tracks = listOf(
        AudioTrack(index = 2, language = "eng"),
        AudioTrack(index = 7, language = "fra"),
    )

    @Test
    fun `player ordinal maps to stable server audio index`() {
        assertEquals(7, selectedServerAudioTrackIndex(selectedOrdinal = 1, audioTracks = tracks))
    }

    @Test
    fun `server audio index maps back to player ordinal`() {
        assertEquals(1, selectedAudioTrackOrdinal(selectedServerIndex = 7, audioTracks = tracks))
    }

    @Test
    fun `legacy ordinal response remains usable when no server index matches`() {
        assertEquals(0, selectedAudioTrackOrdinal(selectedServerIndex = 0, audioTracks = tracks))
    }
}
