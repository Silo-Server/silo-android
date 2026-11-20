package org.siloserver.silo.common.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleSafeInsetTest {
    @Test
    fun pullsTheSurfaceInOnEveryEdge() {
        val rect = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)
        val safe = rect.insetByTitleSafe(0.05f)
        assertEquals(96, safe.left)
        assertEquals(54, safe.top)
        assertEquals(1728, safe.width)
        assertEquals(972, safe.height)
    }

    @Test
    fun zeroIsANoOpSoPhonesAreUntouched() {
        val rect = SubtitleVideoRect(left = 10, top = 20, width = 800, height = 400)
        assertEquals(rect, rect.insetByTitleSafe(0f))
    }

    @Test
    fun refusesAnInsetThatWouldConsumeTheSurface() {
        val rect = SubtitleVideoRect(left = 0, top = 0, width = 100, height = 100)
        assertEquals(rect, rect.insetByTitleSafe(0.6f))
    }
}
