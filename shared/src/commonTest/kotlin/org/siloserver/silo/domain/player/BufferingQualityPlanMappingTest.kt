package org.siloserver.silo.domain.player

import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlaybackDegradationWarning
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackSourceDescriptorV3
import org.siloserver.silo.model.playback.QUALITY_REDUCTION_UNAVAILABLE_WARNING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BufferingQualityPlanMappingTest {
    private fun plan(
        recipeKbps: Int? = null,
        sourceKbps: Int = 0,
        warnings: List<String> = emptyList(),
    ) = PlaybackExecutionPlan(
        planId = "p1",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        routeFamily = PlaybackRouteFamily.PLATFORM_NATIVE,
        effectiveRecipe = PlaybackEffectiveRecipeV3(bitrateKbps = recipeKbps),
        sourceFile = PlaybackSourceDescriptorV3(bitrateKbps = sourceKbps),
        availableQualities = listOf(
            PlaybackAvailableQualityV3(label = "original", bitrateKbps = 63000, preservesSource = true),
            PlaybackAvailableQualityV3(label = "1080p-high", height = 1080, bitrateKbps = 10000),
        ),
        degradationWarnings = warnings.map { PlaybackDegradationWarning(code = it, message = it) },
    )

    @Test
    fun deliveredBitrateIsTheRecipeThenTheSource() {
        assertEquals(31600, plan(recipeKbps = 31600, sourceKbps = 63000).toBufferingQualityPlan("auto", adoptionId = "p1#1").deliveredKbps)
        assertEquals(63000, plan(recipeKbps = null, sourceKbps = 63000).toBufferingQualityPlan("auto", adoptionId = "p1#1").deliveredKbps)
        assertEquals(63000, plan(recipeKbps = 0, sourceKbps = 63000).toBufferingQualityPlan("auto", adoptionId = "p1#1").deliveredKbps)
        assertNull(plan().toBufferingQualityPlan("auto", adoptionId = "p1#1").deliveredKbps)
    }

    @Test
    fun qualityRungsAndWarningMap() {
        val mapped = plan(warnings = listOf("other", QUALITY_REDUCTION_UNAVAILABLE_WARNING))
            .toBufferingQualityPlan("1080P-High", adoptionId = "p1#7")
        assertEquals("p1#7", mapped.planId)
        assertEquals("p1", mapped.serverPlanId)
        assertEquals("1080p-high", mapped.quality)
        assertEquals(
            listOf(BufferingQualityRung("original", 63000), BufferingQualityRung("1080p-high", 10000)),
            mapped.availableQualities,
        )
        assertTrue(mapped.qualityReductionUnavailable)

        val plain = plan(warnings = listOf("other")).toBufferingQualityPlan(" ", adoptionId = "p1#8")
        assertEquals("auto", plain.quality)
        assertFalse(plain.qualityReductionUnavailable)
    }
}
