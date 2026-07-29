package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
