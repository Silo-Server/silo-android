package com.continuum.app.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackSelectionPresetsSourceTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/common/player/TrackSelectionPresets.kt",
    ).readText()

    @Test
    fun presetsDoNotForceDisableSubtitles() {
        assertFalse(
            source.contains("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)"),
            "presets must not force-disable subtitles — doing so wipes the user's active subtitle override on every caps change",
        )
    }

    @Test
    fun presetsApplyPreferredTextLanguage() {
        val occurrences = source.split("setPreferredTextLanguage(").size - 1
        assertEquals(
            2,
            occurrences,
            "setPreferredTextLanguage( must appear exactly twice — once per builder (TV and Phone)",
        )
    }

    @Test
    fun tvPresetDoesNotForceMedia3Tunneling() {
        assertFalse(
            source.contains("setTunnelingEnabled(true)"),
            "TV presets must not force Media3 tunneling; on Google TV Streamer this can leave playback buffered but stuck in AV sync.",
        )
        assertTrue(
            source.contains("setTunnelingEnabled(false)"),
            "TV presets should explicitly leave tunneling off while keeping passthrough/offload preferences.",
        )
    }
}
