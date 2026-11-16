package org.siloserver.silo.android.ui.navigation

import androidx.compose.runtime.saveable.Saver
import kotlin.math.sign

/**
 * Decides when the phone tab bar minimizes to the current tab's control
 * (iOS `tabBarMinimizeBehavior(.onScrollDown)`), from the vertical scroll the
 * tab's content actually consumed.
 *
 * Distances are in pixels of content movement: positive moves the content
 * toward its end (the finger moving up), negative back toward its top.
 *
 * - A run of [minimizeDistance] toward the end minimizes. A run restarts
 *   whenever the direction flips, so jitter does not minimize the bar.
 * - Only reaching the top expands it again, as on iOS; scrolling back toward
 *   the top mid-page leaves it minimized.
 * - Short pages never minimize (iOS `pageIsTooShortToMinimize`). The tracker
 *   follows how far the content sits below its top; reaching the end less than
 *   one [viewportHeight] below the top marks the page short, which expands the
 *   bar and keeps it expanded until the content is seen more than
 *   [shortPageHysteresis] past one viewport. Content that cannot scroll consumes
 *   nothing, so it never minimizes in the first place. While the position is
 *   unknown (content restored mid-scroll that the tracker has not followed),
 *   no page is judged short; reaching the top makes it known again.
 *
 * [saver] keeps the position and the short-page verdict for exactly as long as
 * the page keeps its own scroll position, so a page restored near its end is
 * not mistaken for a short one.
 */
internal class BottomNavMinimizeTracker(
    private val minimizeDistance: Float,
    private val shortPageHysteresis: Float,
) {
    /** Height of the area the tab content scrolls in. Zero disables the short-page check. */
    var viewportHeight: Float = 0f

    var minimized: Boolean = false
        private set

    /** Content movement since the last direction change; the sign is the direction. */
    private var run = 0f

    /** How far the content sits below its top; null while unknown. */
    private var travelFromTop: Float? = 0f

    private var shortPage = false

    /** The content moved by [contentDelta]; returns whether the bar is now minimized. */
    fun onContentScrolled(contentDelta: Float): Boolean {
        if (contentDelta == 0f || contentDelta.isNaN()) return minimized
        val travel = travelFromTop?.let { (it + contentDelta).coerceAtLeast(0f) }
        travelFromTop = travel
        if (shortPage && travel != null && travel > viewportHeight + shortPageHysteresis) shortPage = false

        run = if (run.sign == contentDelta.sign) run + contentDelta else contentDelta
        if (!minimized && !shortPage && run >= minimizeDistance) {
            minimized = true
            run = 0f
        }
        return minimized
    }

    /** A user scroll or fling ran into the top of the content. */
    fun onReachedTop() {
        travelFromTop = 0f
        expand()
    }

    /** A user scroll or fling ran into the end of the content. */
    fun onReachedEnd() {
        val travel = travelFromTop ?: return
        if (viewportHeight > 0f && travel < viewportHeight) {
            shortPage = true
            expand()
        }
    }

    /** The page is at its top without a user scroll running into it (scroll-to-top on reselect). */
    fun onScrolledToTop() = onReachedTop()

    /**
     * The page moved itself somewhere other than its top, so the position
     * followed so far no longer holds. The bar stays as it is.
     */
    fun onPageMovedItself() {
        travelFromTop = null
    }

    /**
     * Different content now fills the tab: start expanded and forget the old
     * page's length. [startsAtTop] says whether the new content starts at its
     * top (a new library or filter) or at a position of its own that the
     * tracker has not followed (a page that kept its scroll position).
     */
    fun onPageChanged(startsAtTop: Boolean) {
        shortPage = false
        travelFromTop = if (startsAtTop) 0f else null
        expand()
    }

    /** Explicit request, such as a tap on the minimized control. */
    fun expand() {
        minimized = false
        run = 0f
    }

    companion object {
        /**
         * Saves what describes the page (its position and short-page verdict),
         * not the bar: a restored tracker starts expanded. Thresholds come from
         * [create], so they follow the current density.
         */
        fun saver(create: () -> BottomNavMinimizeTracker): Saver<BottomNavMinimizeTracker, Any> = Saver(
            save = { floatArrayOf(it.travelFromTop ?: -1f, if (it.shortPage) 1f else 0f) },
            restore = { saved ->
                val values = saved as FloatArray
                create().apply {
                    travelFromTop = values[0].takeIf { it >= 0f }
                    shortPage = values[1] != 0f
                }
            },
        )
    }
}
