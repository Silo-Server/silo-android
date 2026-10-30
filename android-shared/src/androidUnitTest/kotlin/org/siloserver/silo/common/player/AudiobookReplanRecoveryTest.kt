package org.siloserver.silo.common.player

import java.io.IOException
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookReplanRecoveryTest {
    @Test
    fun onlyAnExpiredSessionStartsAFreshOne() {
        val cases = listOf(
            // What the v2 replan answers once the server reaped the session:
            // the not_found problem with the session detail.
            ApiResult.Error(404, "not_found", "Playback session not found")
                to AudiobookReplanRecovery.StartFreshSession,
            // The v1 code for the same condition.
            ApiResult.Error(404, "playback_session_not_found", "Playback session not found")
                to AudiobookReplanRecovery.StartFreshSession,
            // An administrator ended the session: renewing would undo that.
            ApiResult.Error(410, "playback_session_ended", "An administrator ended this playback session")
                to AudiobookReplanRecovery.Fail,
            // Other 404s say nothing about the session.
            ApiResult.Error(404, "not_found", "Source media file is missing")
                to AudiobookReplanRecovery.Fail,
            ApiResult.Error(503, "dependency_unavailable", "Playback is temporarily unavailable.")
                to AudiobookReplanRecovery.Fail,
            ApiResult.NetworkError(IOException("connection refused"))
                to AudiobookReplanRecovery.Fail,
        )
        for ((result, expected) in cases) {
            assertEquals(expected, audiobookReplanRecovery(result), "for $result")
        }
    }
}
