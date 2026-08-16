package org.siloserver.silo.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TV preset must not make `DefaultTrackSelector` a second subtitle
 * authority. A preferred-text hint there enabled a text track on its own while
 * the subtitle transaction adapter's committed identity stayed put — playback
 * obeyed the selector, the HUD reported the adapter, and the two disagreed
 * (subtitles on screen, "Off" in the HUD).
 *
 * Asserted over source because `buildTvParameters` needs a `Context` to produce
 * `DefaultTrackSelector.Parameters`, which a plain JVM unit test cannot provide
 * — the same reason the MIME-preference helpers are tested directly.
 */
class TrackSelectionPresetsTextOwnershipTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("android-shared/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "$signature is missing" }
        val end = source.indexOf("\n    /**", start)
        require(end > start) { "Could not delimit $signature" }
        return source.substring(start, end)
    }

    @Test
    fun tvPresetLeavesTextTrackSelectionToTheApp() {
        val source = source("org/siloserver/silo/common/player/TrackSelectionPresets.kt")
        val tv = functionBody(source, "fun buildTvParameters(")

        assertFalse(tv.contains("setPreferredTextLanguage"))
        assertFalse(tv.contains("preferredTextLanguage"))
        // Text enablement is left entirely untouched, so re-applying presets on
        // a capability change cannot disturb an already-mounted subtitle.
        assertFalse(tv.contains("TRACK_TYPE_TEXT"))
        // Audio language IS still a selector preference on TV.
        assertTrue(tv.contains("setPreferredAudioLanguage"))
    }

    @Test
    fun phonePresetStillHonoursThePreferredTextLanguage() {
        val source = source("org/siloserver/silo/common/player/TrackSelectionPresets.kt")
        val phone = functionBody(source, "fun buildPhoneParameters(")

        assertTrue(phone.contains("setPreferredTextLanguage"))
    }

    @Test
    fun theFactoryDoesNotForwardAPreferredTextLanguageOnTv() {
        val source = source("org/siloserver/silo/common/player/SiloPlayerFactory.kt")
        val tvCall = source.substringAfter("TrackSelectionPresets.buildTvParameters(")
            .substringBefore(")")

        assertFalse(tvCall.contains("preferredTextLanguage"))
    }
}
