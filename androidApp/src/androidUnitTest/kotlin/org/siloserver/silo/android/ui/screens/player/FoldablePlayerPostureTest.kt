package org.siloserver.silo.android.ui.screens.player

import androidx.window.layout.FoldingFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoldablePlayerPostureTest {

    @Test
    fun `half-open horizontal separating fold is tabletop`() {
        assertTrue(
            isTabletopPlayerPosture(
                state = FoldingFeature.State.HALF_OPENED,
                orientation = FoldingFeature.Orientation.HORIZONTAL,
                isSeparating = true,
            ),
        )
    }

    @Test
    fun `book posture and flat folds retain the normal player`() {
        assertFalse(
            isTabletopPlayerPosture(
                state = FoldingFeature.State.HALF_OPENED,
                orientation = FoldingFeature.Orientation.VERTICAL,
                isSeparating = true,
            ),
        )
        assertFalse(
            isTabletopPlayerPosture(
                state = FoldingFeature.State.FLAT,
                orientation = FoldingFeature.Orientation.HORIZONTAL,
                isSeparating = true,
            ),
        )
        assertFalse(
            isTabletopPlayerPosture(
                state = FoldingFeature.State.HALF_OPENED,
                orientation = FoldingFeature.Orientation.HORIZONTAL,
                isSeparating = false,
            ),
        )
    }

    @Test
    fun `pane layout reserves the physical hinge and guard space`() {
        assertEquals(
            TabletopPlayerPaneLayout(
                videoHeightPx = 480,
                controlsHeightPx = 480,
            ),
            calculateTabletopPlayerPaneLayout(
                rootTopPx = 0,
                rootBottomPx = 1000,
                foldTopPx = 490,
                foldBottomPx = 510,
                foldGuardPx = 10,
            ),
        )
    }

    @Test
    fun `zero-height crease still gets guarded`() {
        assertEquals(
            TabletopPlayerPaneLayout(
                videoHeightPx = 492,
                controlsHeightPx = 492,
            ),
            calculateTabletopPlayerPaneLayout(
                rootTopPx = 100,
                rootBottomPx = 1100,
                foldTopPx = 600,
                foldBottomPx = 600,
                foldGuardPx = 8,
            ),
        )
    }

    @Test
    fun `fold outside usable root does not produce tabletop panes`() {
        assertNull(
            calculateTabletopPlayerPaneLayout(
                rootTopPx = 100,
                rootBottomPx = 1100,
                foldTopPx = 50,
                foldBottomPx = 80,
                foldGuardPx = 8,
            ),
        )
    }
}
