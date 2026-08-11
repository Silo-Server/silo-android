package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackSourceMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackColorRangeFallbackTest {
    @Test
    fun preservesValidatedRangeForOriginalDelivery() {
        assertEquals("tv", plan(PlaybackDelivery.ORIGINAL_HTTP, "tv").validatedColorRangeFallback())
    }

    @Test
    fun preservesValidatedRangeForProgressiveRemuxDelivery() {
        assertEquals(
            "pc",
            plan(PlaybackDelivery.SERVER_REMUX_PROGRESSIVE, "pc").validatedColorRangeFallback(),
        )
    }

    @Test
    fun rejectsSourceRangeForTranscodeDelivery() {
        assertNull(plan(PlaybackDelivery.SERVER_TRANSCODE_HLS, "tv").validatedColorRangeFallback())
    }

    @Test
    fun rejectsSourceRangeForClientLocalNormalization() {
        assertNull(
            plan(
                PlaybackDelivery.CLIENT_LOCAL_NORMALIZATION,
                "pc",
            ).validatedColorRangeFallback(),
        )
    }

    @Test
    fun rejectsUnknownSourceRange() {
        assertNull(plan(PlaybackDelivery.ORIGINAL_HTTP, "unknown").validatedColorRangeFallback())
    }

    private fun plan(delivery: PlaybackDelivery, colorRange: String): PlaybackExecutionPlan =
        PlaybackExecutionPlan(
            planId = "plan",
            delivery = delivery,
            routeFamily = PlaybackRouteFamily.PLATFORM_NATIVE,
            source = PlaybackSourceMetadata(colorRange = colorRange),
        )
}
