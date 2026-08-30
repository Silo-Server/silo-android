package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The mounted-window facts feeding decideSeek's mountedSeekableSourceRange
 * hint describe exactly one Media3 item. They must be dropped while a
 * transport handoff suppresses reports and cleared when a mount wins —
 * otherwise a stale extent maps through the new plan's timeline offset and a
 * target the new transport cannot serve becomes a silent, wrong-position
 * native seek.
 */
class TvPlayerWindowFactInvalidationTest {
    private val viewModelSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
    ).readText()

    /** Old-item window facts recorded during a handoff would map through the new plan's offset. */
    @Test
    fun `window facts are not recorded while a transport handoff suppresses reports`() {
        val body = viewModelSource
            .substringAfter("fun onPlayerWindowChanged(")
            .substringBefore("fun onPositionChanged(")

        assertTrue(
            "suppressPositionReports" in body,
            "onPlayerWindowChanged must drop old-item facts during the handoff window",
        )
    }

    /** The queued after-mount seek is evaluated at maximal staleness; the reset must precede it. */
    @Test
    fun `a won mount clears the window facts before re-evaluating queued seeks`() {
        val body = viewModelSource
            .substringAfter("fun onTransportMountApplied(")
            .substringBefore("private fun resetSeekRecoveryForContentChange")
        val resetIndex = body.indexOf("playerWindowIsSeekable = false")
        val queuedSeekIndex = body.indexOf("pendingNativeSeekAfterMount?.let")

        assertTrue(
            resetIndex >= 0,
            "onTransportMountApplied must clear playerWindowIsSeekable when a mount wins",
        )
        assertTrue(
            queuedSeekIndex > resetIndex,
            "the window-fact reset must precede the pendingNativeSeekAfterMount " +
                "re-evaluation, which runs at the moment of maximal staleness",
        )
    }
}
