package org.siloserver.silo.android.ui.screens.libraries

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockSegmentLayoutTest {
    /** Labels whose width scales with the font size: [base] px at 10sp. */
    private fun labels(vararg base: Float): (Float) -> List<Float> = { sp -> base.map { it * sp / 10f } }

    private fun layout(total: Float, selected: Int = 0, vararg base: Float) = dockSegmentLayout(
        labelWidths = labels(*base),
        selectedIndex = selected,
        totalPx = total,
        iconPx = 40f,
        padPx = 20f,
        maxSp = 13f,
        minSp = 10f,
    )

    @Test
    fun roomyWidthKeepsEveryLabelAtFullSize() {
        val result = layout(1000f, 0, 150f, 100f, 120f)
        assertEquals(13f, result.labelSp)
        assertTrue(result.showsLabel.all { it })
        assertEquals(1000f, result.widths.sum(), 0.01f)
    }

    @Test
    fun tighterWidthStepsTheLabelSizeDown() {
        // At 10sp: 370 + 60 padding = 430; at 13sp it would be 541.
        val result = layout(450f, 0, 150f, 100f, 120f)
        assertTrue(result.labelSp < 13f)
        assertTrue(result.showsLabel.all { it })
        assertEquals(450f, result.widths.sum(), 0.01f)
    }

    @Test
    fun tooNarrowForLabelsShowsTheSelectedLabelWithIcons() {
        // Labels need 430 at 10sp; selected label 100 + two icons 80 + 60 = 240.
        val result = layout(300f, 1, 150f, 100f, 120f)
        assertEquals(listOf(false, true, false), result.showsLabel)
        assertEquals(300f, result.widths.sum(), 0.01f)
    }

    @Test
    fun largeTextFallsBackToIconsOnly() {
        // A long translation or a large font scale: even the selected label alone overflows.
        val result = layout(200f, 0, 600f, 500f, 550f)
        assertTrue(result.showsLabel.none { it })
        assertEquals(200f, result.widths.sum(), 0.01f)
    }

    @Test
    fun segmentsStayOnScreenWhenEvenIconsOverflow() {
        val result = layout(60f, 0, 600f, 500f, 550f)
        assertEquals(60f, result.widths.sum(), 0.01f)
        assertTrue(result.widths.all { it > 0f })
    }
}
