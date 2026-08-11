package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TvScrubPreviewPolicyTest {
    @Test
    fun unknownDurationDoesNotClampScrubPreviewToZero() {
        assertEquals(90.0, clampTvScrubPreview(seconds = 90.0, duration = 0.0))
    }

    @Test
    fun knownDurationClampsScrubPreviewToTheSourceRuntime() {
        assertEquals(120.0, clampTvScrubPreview(seconds = 150.0, duration = 120.0))
    }
}
