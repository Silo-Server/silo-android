package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertFalse

class PlaybackSessionLifecycleLoggingTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionLifecycle.kt",
    ).readLines()

    @Test
    fun lifecycleLogsDoNotIncludeRawSessionIds() {
        val logLines = source.filter { it.contains("Log.") }

        assertFalse(
            logLines.any { it.contains("staleSessionId") || it.contains("currentSession.sessionId") },
            "Playback lifecycle logs must not include raw playback session identifiers",
        )
    }
}
