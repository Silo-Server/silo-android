package org.siloserver.silo.model.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BufferingQualityRequestGatingTest {
    private val context = ClientPlaybackContext(formFactor = "tv", appVersion = "test")

    private fun start(learnedLimit: String? = LEARNED_LIMIT_IGNORE) = PlaybackStartRequestV3(
        clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
        fileId = 42,
        profileId = "profile",
        playbackAttemptId = "attempt",
        subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
        learnedLimit = learnedLimit,
        capabilities = ClientCodecCapabilities(),
        clientPlaybackContext = context,
    )

    private fun replan(
        estimate: Int? = 44100,
        source: String? = BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER,
        learnedLimit: String? = null,
    ) = PlaybackReplanRequestV3(
        clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
        operation = QUALITY_CHANGE_V3_OPERATION,
        playbackAttemptId = "attempt",
        replanRequestId = "replan",
        failedPlanId = "plan",
        planAttemptId = "plan-attempt",
        planAttemptKey = "key",
        attemptedPlanKeys = emptyList(),
        attemptCount = 1,
        positionSeconds = 12.0,
        bandwidthEstimateKbps = estimate,
        bandwidthEstimateSource = source,
        learnedLimit = learnedLimit,
        selectedTracks = SelectedPlaybackTracksV3(),
        capabilities = ClientCodecCapabilities(),
        clientPlaybackContext = context,
    )

    private val advertising = listOf(SEQUENCED_PROGRESS, BUFFERING_QUALITY_V1_FEATURE)
    private val older = listOf(SEQUENCED_PROGRESS)

    @Test
    fun advertisingServerGetsTheFieldsAndTheClientFeature() {
        val gatedStart = start().gatedForBufferingQuality(advertising)
        assertEquals(LEARNED_LIMIT_IGNORE, gatedStart.learnedLimit)
        assertEquals(PLAYBACK_START_CLIENT_FEATURES_V3 + BUFFERING_QUALITY_V1_FEATURE, gatedStart.clientFeatures)

        val gatedReplan = replan(learnedLimit = LEARNED_LIMIT_CLEAR).gatedForBufferingQuality(advertising)
        assertEquals(44100, gatedReplan.bandwidthEstimateKbps)
        assertEquals(BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER, gatedReplan.bandwidthEstimateSource)
        assertEquals(LEARNED_LIMIT_CLEAR, gatedReplan.learnedLimit)
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in gatedReplan.clientFeatures)
    }

    @Test
    fun olderServerLosesTheNewFieldsButKeepsTheEstimate() {
        val gatedStart = start().gatedForBufferingQuality(older)
        assertNull(gatedStart.learnedLimit)
        assertEquals(PLAYBACK_START_CLIENT_FEATURES_V3, gatedStart.clientFeatures)

        val gatedReplan = replan(learnedLimit = LEARNED_LIMIT_IGNORE).gatedForBufferingQuality(older)
        assertEquals(44100, gatedReplan.bandwidthEstimateKbps)
        assertNull(gatedReplan.bandwidthEstimateSource)
        assertNull(gatedReplan.learnedLimit)
        assertFalse(BUFFERING_QUALITY_V1_FEATURE in gatedReplan.clientFeatures)
    }

    @Test
    fun gatingIsIdempotentAndLeavesAPlainRequestAlone() {
        val once = replan().gatedForBufferingQuality(advertising)
        assertEquals(once, once.gatedForBufferingQuality(advertising))
        assertEquals(1, once.clientFeatures.count { it == BUFFERING_QUALITY_V1_FEATURE })

        val plain = replan(estimate = null, source = null)
        assertEquals(plain, plain.gatedForBufferingQuality(older))
        assertEquals(start(learnedLimit = null), start(learnedLimit = null).gatedForBufferingQuality(older))
    }

    @Test
    fun aSourceWithoutAnEstimateIsNeverSent() {
        // The server answers 400 to a source without an estimate.
        assertNull(replan(estimate = null).gatedForBufferingQuality(advertising).bandwidthEstimateSource)
        assertNull(probeStart(estimate = null).gatedForBufferingQuality(advertising).bandwidthEstimateSource)
    }

    private fun probeStart(estimate: Int? = 48_000) = start(learnedLimit = null).copy(
        bandwidthEstimateKbps = estimate,
        bandwidthEstimateSource = BANDWIDTH_ESTIMATE_SOURCE_PROBE,
    )

    @Test
    fun aProbeEstimateGoesOutWithItsSourceToAnAdvertisingServer() {
        val gated = probeStart().gatedForBufferingQuality(advertising)
        assertEquals(48_000, gated.bandwidthEstimateKbps)
        assertEquals(BANDWIDTH_ESTIMATE_SOURCE_PROBE, gated.bandwidthEstimateSource)
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in gated.clientFeatures)
        assertEquals(gated, gated.gatedForBufferingQuality(advertising))
    }

    @Test
    fun anOlderServerGetsTheProbeEstimateWithoutItsSource() {
        val gated = probeStart().gatedForBufferingQuality(older)
        assertEquals(48_000, gated.bandwidthEstimateKbps)
        assertNull(gated.bandwidthEstimateSource)
        assertFalse(BUFFERING_QUALITY_V1_FEATURE in gated.clientFeatures)
    }

    private companion object {
        const val SEQUENCED_PROGRESS = "sequenced_progress_v1"
    }
}
