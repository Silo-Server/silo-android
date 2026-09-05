package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileRecoveryReplanTest {
    @Test
    fun subtitleFallbackErrorsPreserveVideoButOtherRecoveryErrorsRemainFatal() {
        val native = MobileRecoveryReplan("subtitle_embedded_failed", "Retrying", 4)
        val transport = MobileRecoveryReplan("transport_stall", "Retrying")
        val failures: List<ApiResult<VideoSessionStartV3>> = listOf(
            ApiResult.Error(code = 503, error = "unavailable", message = "Unavailable"),
            ApiResult.NetworkError(IllegalStateException("Offline")),
            ApiResult.Success(VideoSessionStartV3.Terminal("unsupported", "No subtitles", false)),
            ApiResult.Success(VideoSessionStartV3.ServerUpgradeRequired),
        )
        failures.forEach { failure ->
            assertTrue(native.isNonfatalFailure(failure), "$failure")
            assertFalse(transport.isNonfatalFailure(failure), "$failure")
        }
    }

    @Test
    fun successfulSubtitleFallbackStillReachesSessionAdoption() {
        val ready = VideoSessionStartV3.Ready(
            session = PlaybackSessionResponse("s2", 1, mediaFileId = 42, playMethod = PlayMethod.DIRECT, streamUrl = "/stream/s2"),
            plan = PlaybackPlanV3(
                planId = "p2", planAttemptKey = "key", delivery = PlaybackDelivery.ORIGINAL_HTTP,
                stream = PlaybackStreamV3("/stream/s2", PlaybackStreamProtocol.HTTP_PROGRESSIVE),
                decisionReason = "sidecar",
            ),
            playbackAttemptId = "attempt", planAttemptId = "plan-attempt", planAttemptKey = "key",
            capabilities = ClientCodecCapabilities(),
            clientPlaybackContext = ClientPlaybackContext(formFactor = "phone", appVersion = "test"),
        )
        assertFalse(MobileRecoveryReplan("subtitle_embedded_failed", "Retrying", 4).isNonfatalFailure(ApiResult.Success(ready)))
    }

    @Test
    fun nativeFailureQueuesTheUncommittedTrackInsteadOfReusingTheCurrentSelection() {
        val failure = MobileRecoveryReplan("subtitle_embedded_failed", "Retrying", 4)
        assertTrue(failure.shouldQueue)
        assertEquals(4, failure.subtitleTrackIndexOverride)
        assertEquals("subtitle_embedded_failed", failure.classification)
        assertFalse(MobileRecoveryReplan("transport_stall", "Retrying").shouldQueue)
    }
}
