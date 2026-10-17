package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerVerticalDragModeTest {
    @Test
    fun `left edge leaves system brightness authoritative`() {
        assertEquals(
            VerticalDragMode.None,
            verticalDragMode(startX = 40f, width = 1_000f, edgeZonePx = 88f),
        )
    }

    @Test
    fun `right edge retains volume routing`() {
        assertEquals(
            VerticalDragMode.Volume,
            verticalDragMode(startX = 950f, width = 1_000f, edgeZonePx = 88f),
        )
    }

    @Test
    fun `center retains dismiss routing`() {
        assertEquals(
            VerticalDragMode.DismissCandidate,
            verticalDragMode(startX = 500f, width = 1_000f, edgeZonePx = 88f),
        )
    }

    @Test
    fun `slow volume drag moves by whole-drag distance, not per event`() {
        // 15-step stream over 300px: 20px per step. Ten 2px events used to
        // truncate to zero each; the whole 20px drag is one step.
        assertEquals(8, volumeIndexForDrag(startIndex = 7, maxIndex = 15, dragUpPx = 20f, fullRangePx = 300f))
        assertEquals(7, volumeIndexForDrag(startIndex = 7, maxIndex = 15, dragUpPx = 5f, fullRangePx = 300f))
    }

    @Test
    fun `volume drag clamps to the stream range`() {
        assertEquals(15, volumeIndexForDrag(startIndex = 10, maxIndex = 15, dragUpPx = 1_000f, fullRangePx = 300f))
        assertEquals(0, volumeIndexForDrag(startIndex = 3, maxIndex = 15, dragUpPx = -1_000f, fullRangePx = 300f))
    }

    @Test
    fun `dismiss needs a long, mostly vertical downward drag`() {
        assertTrue(isDismissDrag(dx = 20f, dy = 150f, thresholdPx = 140f))
        assertFalse(isDismissDrag(dx = 0f, dy = 120f, thresholdPx = 140f))
        assertFalse(isDismissDrag(dx = 120f, dy = 150f, thresholdPx = 140f))
    }
}
