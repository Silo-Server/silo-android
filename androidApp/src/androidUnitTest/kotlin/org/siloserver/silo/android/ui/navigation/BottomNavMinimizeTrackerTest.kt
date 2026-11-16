package org.siloserver.silo.android.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BottomNavMinimizeTrackerTest {
    private fun tracker(viewport: Float = 2000f) = BottomNavMinimizeTracker(
        minimizeDistance = 100f,
        shortPageHysteresis = 200f,
    ).apply { viewportHeight = viewport }

    /** Saves [t] as the shell does when its destination leaves the screen, and restores it. */
    private fun saveAndRestore(t: BottomNavMinimizeTracker, viewport: Float = 2000f): BottomNavMinimizeTracker {
        val saver = BottomNavMinimizeTracker.saver { tracker(viewport) }
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val saved = with(saver) { scope.save(t) }!!
        return saver.restore(saved)!!.apply { viewportHeight = viewport }
    }

    private fun BottomNavMinimizeTracker.scroll(vararg deltas: Float) = deltas.forEach { onContentScrolled(it) }

    @Test
    fun minimizesOnlyAfterTheThresholdTowardTheEnd() {
        val t = tracker()
        t.scroll(40f, 40f)
        assertFalse(t.minimized)
        t.scroll(20f)
        assertTrue(t.minimized)
    }

    @Test
    fun scrollingBackTowardTheTopStaysMinimizedUntilTheTop() {
        val t = tracker()
        t.scroll(500f, 500f, 500f)
        assertTrue(t.minimized)
        t.scroll(-500f, -500f, -400f)
        assertTrue(t.minimized)
        t.scroll(-100f)
        t.onReachedTop()
        assertFalse(t.minimized)
    }

    @Test
    fun jitterDoesNotMinimizeTheBar() {
        val t = tracker()
        // Alternating small movements never build a run toward the end.
        repeat(50) { t.scroll(30f, -30f) }
        assertFalse(t.minimized)
    }

    @Test
    fun aReversalRestartsTheRun() {
        val t = tracker()
        t.scroll(80f)
        t.scroll(-10f) // direction flips: the run toward the end starts over
        t.scroll(80f)
        assertFalse(t.minimized)
        t.scroll(20f)
        assertTrue(t.minimized)
    }

    @Test
    fun reachingTheTopExpands() {
        val t = tracker()
        t.scroll(500f, 500f, 500f)
        assertTrue(t.minimized)
        t.onReachedTop()
        assertFalse(t.minimized)
    }

    @Test
    fun zeroDeltasChangeNothing() {
        val t = tracker()
        repeat(100) { t.scroll(0f) }
        assertFalse(t.minimized)
    }

    @Test
    fun aShortPageExpandsAtItsEndAndStaysExpanded() {
        val t = tracker(viewport = 2000f)
        t.scroll(300f) // minimizes on the way down
        assertTrue(t.minimized)
        t.onReachedEnd() // only 300px below the top: shorter than one viewport
        assertFalse(t.minimized)
        // Further drags on the short page do not minimize it again.
        t.scroll(-200f, 250f)
        assertFalse(t.minimized)
    }

    @Test
    fun aShortPageNeedsHysteresisPastOneViewportBeforeItMinimizesAgain() {
        val t = tracker(viewport = 2000f)
        t.scroll(300f)
        t.onReachedEnd()
        // The page grows (more rows loaded); within the hysteresis it stays expanded.
        t.scroll(1800f)
        assertFalse(t.minimized)
        // Beyond viewport + hysteresis it behaves like any tall page.
        t.scroll(200f, 150f)
        assertTrue(t.minimized)
    }

    @Test
    fun aTallPageStaysMinimizedAtItsEnd() {
        val t = tracker(viewport = 2000f)
        t.scroll(1000f, 1000f, 1000f)
        t.onReachedEnd()
        assertTrue(t.minimized)
    }

    @Test
    fun aPageChangeStartsExpandedAndForgetsShortness() {
        val t = tracker(viewport = 2000f)
        t.scroll(300f)
        t.onReachedEnd()
        t.onPageChanged(startsAtTop = true)
        t.scroll(150f)
        assertTrue(t.minimized)
        t.onPageChanged(startsAtTop = true)
        assertFalse(t.minimized)
    }

    @Test
    fun programmaticScrollToTopExpandsAndResetsPosition() {
        val t = tracker(viewport = 2000f)
        t.scroll(5000f)
        assertTrue(t.minimized)
        t.onScrolledToTop()
        assertFalse(t.minimized)
        // Measured from the top again: ending 500px down is a short page.
        t.scroll(500f)
        t.onReachedEnd()
        assertFalse(t.minimized)
    }

    @Test
    fun explicitExpandLetsALaterScrollMinimizeAgain() {
        val t = tracker()
        t.scroll(500f, 500f, 500f)
        t.expand()
        assertFalse(t.minimized)
        t.scroll(60f)
        assertFalse(t.minimized)
        t.scroll(60f)
        assertTrue(t.minimized)
    }

    @Test
    fun noViewportDisablesTheShortPageCheck() {
        val t = tracker(viewport = 0f)
        t.scroll(300f)
        t.onReachedEnd()
        assertTrue(t.minimized)
    }

    @Test
    fun aLongPageRestoredNearItsEndStaysMinimizedAtTheEnd() {
        val t = tracker(viewport = 2000f)
        t.scroll(1000f, 1000f, 1000f, 1000f, 1000f) // most of the way down a long feed
        // Leave for another tab or a detail page; the feed keeps its position.
        val restored = saveAndRestore(t)
        assertFalse(restored.minimized) // re-entering starts expanded
        restored.scroll(400f, 400f)
        assertTrue(restored.minimized)
        restored.onReachedEnd()
        assertTrue(restored.minimized)
    }

    @Test
    fun aShortPageIsStillCaughtAfterARestore() {
        val t = tracker(viewport = 2000f)
        t.scroll(300f)
        val restored = saveAndRestore(t)
        restored.scroll(200f) // minimizes on the way down
        assertTrue(restored.minimized)
        restored.onReachedEnd() // 500px below the top: shorter than one viewport
        assertFalse(restored.minimized)
    }

    @Test
    fun aShortVerdictSurvivesARestore() {
        val t = tracker(viewport = 2000f)
        t.scroll(300f)
        t.onReachedEnd()
        val restored = saveAndRestore(t)
        restored.scroll(-100f, 150f)
        assertFalse(restored.minimized)
    }

    @Test
    fun aPageWithAnUnknownPositionIsNeverJudgedShort() {
        val t = tracker(viewport = 2000f)
        t.onPageChanged(startsAtTop = false) // a page that kept its own scroll position
        t.scroll(300f)
        assertTrue(t.minimized)
        t.onReachedEnd()
        assertTrue(t.minimized)
        // Reaching the top makes the position known again.
        t.onReachedTop()
        t.scroll(300f)
        t.onReachedEnd()
        assertFalse(t.minimized)
    }

    @Test
    fun aPageThatMovedItselfIsNotJudgedShortFromAStalePosition() {
        val t = tracker(viewport = 2000f)
        t.scroll(300f)
        assertTrue(t.minimized)
        // A jump far down a long page that the tracker did not follow.
        t.onPageMovedItself()
        assertTrue(t.minimized)
        t.scroll(200f)
        t.onReachedEnd()
        assertTrue(t.minimized)
    }
}
