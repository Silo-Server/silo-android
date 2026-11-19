package org.siloserver.silo.domain.player

import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.QUALITY_REDUCTION_UNAVAILABLE_WARNING
import org.siloserver.silo.model.playback.videoQualityPreference

/**
 * What an adopted plan plays at: its quality preference, its bandwidth cap
 * and its file. The viewer's quality change (a pick, a version, the phone's
 * Wi-Fi/cellular quality pair) is the adoption the controller did not ask for
 * whose key differs from the last one; a change the server refused never
 * produces that adoption, so it counts as none.
 */
data class BufferingQualityKey(val quality: String, val bandwidthCapKbps: Int?, val fileId: Int?)

/** Whether adopting a plan at [next], after one at this key, is the viewer's quality change. */
fun BufferingQualityKey?.isViewerChangeTo(next: BufferingQualityKey, controllerInitiated: Boolean): Boolean =
    !controllerInitiated && this != null && this != next

/**
 * What [BufferingQualityController.onPlanAdopted] needs from a plan the player
 * just started playing ([PlaybackExecutionPlan.sourceFile] is the wire plan's
 * `source`).
 *
 * [qualityPreference] is the preference the plan was requested with: `auto`,
 * or the rung label the viewer picked, normalized as [videoQualityPreference]
 * normalizes it. Blank reads as `auto`.
 *
 * [adoptionId] identifies this adoption and must be unique per adoption: the
 * server `plan_id` can repeat across replans, so it is kept separately as
 * [BufferingQualityPlan.serverPlanId] for the `plan_summary` route event.
 */
fun PlaybackExecutionPlan.toBufferingQualityPlan(qualityPreference: String, adoptionId: String): BufferingQualityPlan =
    BufferingQualityPlan(
        planId = adoptionId,
        serverPlanId = planId,
        // The spec's delivered bitrate: effective_recipe.bitrate_kbps, else source.bitrate_kbps.
        deliveredKbps = effectiveRecipe?.bitrateKbps?.takeIf { it > 0 }
            ?: sourceFile.bitrateKbps.takeIf { it > 0 },
        quality = videoQualityPreference(qualityPreference) ?: PLAYBACK_QUALITY_AUTO,
        availableQualities = availableQualities.map { BufferingQualityRung(it.label, it.bitrateKbps) },
        qualityReductionUnavailable = degradationWarnings.any { it.code == QUALITY_REDUCTION_UNAVAILABLE_WARNING },
        activeQuality = activeQuality?.takeIf { activeQualityReported },
    )
