package org.siloserver.silo.android.ui.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponsiveControlLayoutTest {
    @Test
    fun audiobookTransportKeepsPreferredSpacingWhenItFits() {
        assertEquals(
            AudiobookTransportLayout(spacingDp = 28f, requiresHorizontalScroll = false),
            resolveAudiobookTransportLayout(availableWidthDp = 390f, hasChapters = true),
        )
    }

    @Test
    fun audiobookTransportCompactsSpacingAtTypicalNarrowPhoneWidth() {
        assertEquals(
            AudiobookTransportLayout(spacingDp = 8.5f, requiresHorizontalScroll = false),
            resolveAudiobookTransportLayout(availableWidthDp = 312f, hasChapters = true),
        )
    }

    @Test
    fun audiobookTransportScrollsWhenMinimumSpacingCannotFit() {
        assertEquals(
            AudiobookTransportLayout(spacingDp = 8f, requiresHorizontalScroll = true),
            resolveAudiobookTransportLayout(availableWidthDp = 300f, hasChapters = true),
        )
    }
}
