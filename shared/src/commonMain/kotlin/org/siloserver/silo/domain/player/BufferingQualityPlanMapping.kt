package org.siloserver.silo.domain.player

import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.QUALITY_REDUCTION_UNAVAILABLE_WARNING

/**
 * What [BufferingQualityController.onPlanAdopted] needs from a plan the player
 * just started playing.
 *
 * [qualityPreference] is the preference the plan was requested with: `auto`,
 * or the rung label the viewer picked. Blank reads as `auto`.
 *
 * [adoptionId] identifies this adoption and must be unique per adoption: the
 * server `plan_id` can repeat across replans, so it is kept separately as
 * [BufferingQualityPlan.serverPlanId] for the `plan_summary` route event.
 */
fun PlaybackPlanV3.toBufferingQualityPlan(qualityPreference: String, adoptionId: String): BufferingQualityPlan =
    BufferingQualityPlan(
        planId = adoptionId,
        serverPlanId = planId,
        // The spec's delivered bitrate: effective_recipe.bitrate_kbps, else source.bitrate_kbps.
        deliveredKbps = effectiveRecipe.bitrateKbps?.takeIf { it > 0 }
            ?: source.bitrateKbps.takeIf { it > 0 },
        quality = qualityPreference.trim().lowercase().ifBlank { BufferingQualityController.QUALITY_AUTO },
        availableQualities = availableQualities.map { BufferingQualityRung(it.label, it.bitrateKbps) },
        qualityReductionUnavailable = degradationWarnings.any { it.code == QUALITY_REDUCTION_UNAVAILABLE_WARNING },
    )

/**
 * The player's projection of a plan as the buffering-quality controller
 * reads it: the same facts `PlaybackPlanV3.toBufferingQualityPlan` takes from
 * the wire plan ([PlaybackExecutionPlan.sourceFile] is the plan's `source`).
 * [qualityPreference] is `auto` or the rung the plan was requested with;
 * [adoptionId] is the unique adoption identity, as for the wire plan.
 */
fun PlaybackExecutionPlan.toBufferingQualityPlan(qualityPreference: String, adoptionId: String): BufferingQualityPlan =
    BufferingQualityPlan(
        planId = adoptionId,
        serverPlanId = planId,
        // Delivered bitrate: effective_recipe.bitrate_kbps, else source.bitrate_kbps.
        deliveredKbps = effectiveRecipe?.bitrateKbps?.takeIf { it > 0 }
            ?: sourceFile.bitrateKbps.takeIf { it > 0 },
        quality = qualityPreference.trim().lowercase().ifBlank { BufferingQualityController.QUALITY_AUTO },
        availableQualities = availableQualities.map { BufferingQualityRung(it.label, it.bitrateKbps) },
        qualityReductionUnavailable = degradationWarnings.any { it.code == QUALITY_REDUCTION_UNAVAILABLE_WARNING },
    )
