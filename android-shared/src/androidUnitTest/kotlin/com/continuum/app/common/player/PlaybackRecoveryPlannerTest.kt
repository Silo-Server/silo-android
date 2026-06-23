package com.continuum.app.common.player

import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind
import com.continuum.app.model.playback.PlaybackExecutionPlan
import com.continuum.app.model.playback.PlaybackFallbackCandidate
import com.continuum.app.model.playback.PlaybackRouteFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaybackRecoveryPlannerTest {
    private fun decoderError() = androidx.media3.common.PlaybackException(
        "decoder failed",
        null,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    )

    @Test
    fun unsupportedAudioCodecTranscodesInsteadOfRemuxingTheSameAudio() {
        // A remux copies the audio stream verbatim, so it can't fix an audio
        // codec the sink rejected. Even with a remux candidate present, recovery
        // must transcode (re-encode), not remux.
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

        assertIs<PlaybackRecoveryAction.ServerTranscode>(action)
    }

    @Test
    fun playerErrorPrefersServerRemuxCandidateBeforeFullTranscode() {
        val plan = PlaybackExecutionPlan(
            planId = "s1",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            engine = PlaybackEngineKind.MEDIA3_DIRECT,
            routeFamily = PlaybackRouteFamily.PLATFORM_NATIVE,
            fallbacks = listOf(
                PlaybackFallbackCandidate(
                    delivery = PlaybackDelivery.SERVER_REMUX_HLS,
                    engine = PlaybackEngineKind.MEDIA3_HLS,
                    reason = "container_adaptation",
                ),
            ),
        )

        val action = PlaybackRecoveryPlanner().planForPlayerError(plan, decoderError())

        val remux = assertIs<PlaybackRecoveryAction.ServerRemux>(action)
        // #9: the chosen remux delivery is carried, not collapsed to HLS blindly.
        assertEquals(PlaybackDelivery.SERVER_REMUX_HLS, remux.delivery)
    }

    @Test
    fun serverRemuxCarriesProgressiveDeliveryWhenThatIsTheCandidate() {
        val plan = PlaybackExecutionPlan(
            planId = "s1",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            engine = PlaybackEngineKind.MEDIA3_DIRECT,
            routeFamily = PlaybackRouteFamily.PLATFORM_NATIVE,
            fallbacks = listOf(
                PlaybackFallbackCandidate(
                    delivery = PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
                    engine = PlaybackEngineKind.MEDIA3_PROGRESSIVE_REMUX,
                    reason = "container_adaptation",
                ),
            ),
        )

        val action = PlaybackRecoveryPlanner().planForPlayerError(plan, decoderError())

        val remux = assertIs<PlaybackRecoveryAction.ServerRemux>(action)
        assertEquals(PlaybackDelivery.SERVER_REMUX_PROGRESSIVE, remux.delivery)
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

        val action = PlaybackRecoveryPlanner().planForPlayerError(plan, decoderError())

        val alternate = assertIs<PlaybackRecoveryAction.AlternateDirectEngine>(action)
        assertEquals(PlaybackEngineKind.MPV_DIRECT, alternate.engine)
    }

    @Test
    fun alreadyAttemptedEnginesAreNotReselected() {
        // #6: now on MPV_DIRECT after a prior swap; the only alternate ORIGINAL_HTTP
        // candidate is MEDIA3_DIRECT, which we already tried. The planner must NOT
        // bounce back to it (ping-pong) and should fall through to a server route.
        val plan = PlaybackExecutionPlan(
            planId = "s1",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            engine = PlaybackEngineKind.MPV_DIRECT,
            routeFamily = PlaybackRouteFamily.COMPATIBILITY_DIRECT,
            fallbacks = listOf(
                PlaybackFallbackCandidate(
                    delivery = PlaybackDelivery.ORIGINAL_HTTP,
                    engine = PlaybackEngineKind.MEDIA3_DIRECT,
                    reason = "alternate_direct_engine",
                ),
                PlaybackFallbackCandidate(
                    delivery = PlaybackDelivery.SERVER_REMUX_HLS,
                    engine = PlaybackEngineKind.MEDIA3_HLS,
                    reason = "container_adaptation",
                ),
            ),
        )

        val action = PlaybackRecoveryPlanner().planForPlayerError(
            currentPlan = plan,
            error = decoderError(),
            attemptedEngines = setOf(PlaybackEngineKind.MEDIA3_DIRECT),
        )

        assertIs<PlaybackRecoveryAction.ServerRemux>(action)
    }
}
