package org.siloserver.silo.domain.player

import org.siloserver.silo.model.playback.LEARNED_LIMIT_CLEAR
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.QUALITY_CHANGE_V3_OPERATION
import org.siloserver.silo.model.playback.videoQualityPreference

/**
 * The step-down estimate a playback attempt's Auto was last lowered with
 * (*Later replans after a lowering* in the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`).
 *
 * An Auto replan without an estimate returns to the source unless a learned
 * limit applies, and none does on a server without `buffering_quality_v1` or
 * in `never` mode. So every later Auto replan the client sends for its own
 * reasons (track changes, seek reanchors and recoveries, failure recoveries,
 * output changes) repeats this estimate, without a source because it is not a
 * new observation. It lives in memory only and never stands in for the
 * server's learned limit.
 */
data class KeptStepDownEstimate(val playbackAttemptId: String, val kbps: Int)

/** A replan as it should be sent, and the kept estimate once it has been. */
data class KeptEstimateReplan(
    val request: PlaybackReplanRequestV3,
    val kept: KeptStepDownEstimate?,
)

/**
 * Applies the kept-estimate rule to this request, which [bufferingQuality]
 * asked for or, when null, the client sends for its own reasons. Pure, so the
 * caller decides where [kept] lives; call it once per request, right before
 * sending.
 *
 * - A lowering ([bufferingQuality] with an estimate) keeps its estimate,
 *   replacing any earlier one.
 * - Restore quality (`learned_limit: clear`) and a `quality_change` the
 *   controller did not send (the viewer's pick, or a move between Wi-Fi and
 *   cellular quality) discard the attempt's estimate.
 * - Any other Auto request without an estimate of its own carries the kept
 *   estimate. A new playback attempt never inherits one: it is keyed by the
 *   attempt ID, which every start mints afresh.
 */
fun PlaybackReplanRequestV3.withKeptStepDownEstimate(
    kept: KeptStepDownEstimate?,
    bufferingQuality: BufferingQualityReplan?,
): KeptEstimateReplan {
    val observed = bufferingQuality?.bandwidthEstimateKbps
    val discards = bufferingQuality?.learnedLimit == LEARNED_LIMIT_CLEAR ||
        (bufferingQuality == null && operation == QUALITY_CHANGE_V3_OPERATION)
    if (discards) return KeptEstimateReplan(this, kept?.takeUnless { it.playbackAttemptId == playbackAttemptId })
    if (observed != null) return KeptEstimateReplan(this, KeptStepDownEstimate(playbackAttemptId, observed))
    val repeated = kept?.takeIf {
        it.playbackAttemptId == playbackAttemptId &&
            bandwidthEstimateKbps == null &&
            videoQualityPreference(qualityPreference) == PLAYBACK_QUALITY_AUTO
    } ?: return KeptEstimateReplan(this, kept)
    return KeptEstimateReplan(
        copy(bandwidthEstimateKbps = repeated.kbps, bandwidthEstimateSource = null, observedThroughputKbps = null),
        kept,
    )
}
