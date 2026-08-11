package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun takingOwnershipWaitsForAnAsynchronousStopToFinish() {
        // The lifecycle no longer starts sessions — under protocol v3 planning
        // belongs to the owner — so the two doors into ownership are direct
        // adoption and epoch acquisition. Both must drain a queued teardown
        // first, or an older screen's stop lands on the new session.
        val text = source.joinToString("\n")

        assertTrue(text.contains("private var pendingStopJob: Job?"))
        assertTrue(text.contains("private suspend fun awaitPendingStop()"))
        assertTrue(
            text.substringAfter("suspend fun adoptActiveSession(")
                .substringBefore("suspend fun acquireOwnershipEpoch()")
                .contains("awaitPendingStop()"),
        )
        assertTrue(
            text.substringAfter("suspend fun acquireOwnershipEpoch()")
                .substringBefore("suspend fun adoptActiveSessionIfCurrent(")
                .contains("awaitPendingStop()"),
        )
    }
}
