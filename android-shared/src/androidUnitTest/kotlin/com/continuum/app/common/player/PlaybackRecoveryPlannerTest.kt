package com.continuum.app.common.player

import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind
import com.continuum.app.model.playback.PlaybackExecutionPlan
import com.continuum.app.model.playback.PlaybackFallbackCandidate
import com.continuum.app.model.playback.PlaybackRouteFamily
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class PlaybackRecoveryPlannerTest {
    @Test
    fun directFailurePrefersServerRemuxCandidateBeforeFullTranscode() {
        val plan = PlaybackExecutionPlan(
            planId = "s1",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            engine = PlaybackEngineKind.MEDIA3_DIRECT,
            routeFamily = PlaybackRouteFamily.PLATFORM_NATIVE,
            fallbacks = listOf(
                PlaybackFallbackCandidate(
                    delivery = PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
                    engine = PlaybackEngineKind.MEDIA3_PROGRESSIVE_REMUX,
                    reason = "audio_or_container_adaptation",
                ),
            ),
        )

        val action = PlaybackRecoveryPlanner().planForPlayability(
            currentPlan = plan,
            reason = Playability.UnsupportedAudioCodec("audio/true-hd"),
        )

        assertIs<PlaybackRecoveryAction.ServerRemux>(action)
    }

    @Test
    fun directFailurePrefersAlternateDirectEngineBeforeServerFallback() {
        val plan = PlaybackExecutionPlan(
            planId = "s1",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            engine = PlaybackEngineKind.MEDIA3_DIRECT,
            routeFamily = PlaybackRouteFamily.PLATFORM_NATIVE,
            fallbacks = listOf(
                PlaybackFallbackCandidate(
                    delivery = PlaybackDelivery.ORIGINAL_HTTP,
                    engine = PlaybackEngineKind.MPV_DIRECT,
                    reason = "alternate_direct_engine",
                ),
                PlaybackFallbackCandidate(
                    delivery = PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
                    engine = PlaybackEngineKind.MEDIA3_PROGRESSIVE_REMUX,
                    reason = "audio_or_container_adaptation",
                ),
            ),
        )

        val action = PlaybackRecoveryPlanner().planForPlayerError(
            currentPlan = plan,
            error = androidx.media3.common.PlaybackException(
                "decoder failed",
                null,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            ),
        )

        val alternate = assertIs<PlaybackRecoveryAction.AlternateDirectEngine>(action)
        assertEquals(PlaybackEngineKind.MPV_DIRECT, alternate.engine)
    }
}
