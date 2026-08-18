package org.siloserver.silo.common.ui.components

import org.siloserver.silo.model.settings.PosterSizePreset
import kotlin.test.Test
import kotlin.test.assertEquals

class CardPresentationSizingTest {
    @Test
    fun fixedPosterGridsMoveOneDensityStepPerPreset() {
        assertEquals(7, 6.forPosterPreset(PosterSizePreset.COMPACT))
        assertEquals(6, 6.forPosterPreset(PosterSizePreset.STANDARD))
        assertEquals(5, 6.forPosterPreset(PosterSizePreset.LARGE))
        assertEquals(1, 1.forPosterPreset(PosterSizePreset.LARGE))
    }
}
