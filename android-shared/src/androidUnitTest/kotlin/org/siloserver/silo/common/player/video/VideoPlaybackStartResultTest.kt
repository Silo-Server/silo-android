package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VideoPlaybackStartResultTest {
    @Test
    fun serverTerminalRecognizesTranscodeFailureReasons() {
        assertNotNull(PlaybackDiagnosticsCode.serverTerminal("transcode_start_failed"))
        assertNotNull(PlaybackDiagnosticsCode.serverTerminal("transcode_node_unavailable"))
        assertNotNull(PlaybackDiagnosticsCode.serverTerminal("transcode_node_capability_unavailable"))
    }

    @Test
    fun serverTerminalReturnsNullForUnknownReason() {
        assertNull(PlaybackDiagnosticsCode.serverTerminal("some_unlisted_reason"))
    }
}
