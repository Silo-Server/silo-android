package org.siloserver.silo.android.ui.screens.player

import android.app.Activity
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * Vendor-neutral tabletop posture reported by Jetpack WindowManager. The fold
 * bounds are in window coordinates and let the player avoid the physical
 * crease or hinge rather than assuming the display splits exactly in half.
 */
internal data class TabletopPlayerPosture(
    val foldBounds: Rect,
)

internal data class TabletopPlayerPaneLayout(
    val videoHeightPx: Int,
    val controlsHeightPx: Int,
)

internal fun isTabletopPlayerPosture(
    state: FoldingFeature.State,
    orientation: FoldingFeature.Orientation,
    isSeparating: Boolean,
): Boolean =
    state == FoldingFeature.State.HALF_OPENED &&
        orientation == FoldingFeature.Orientation.HORIZONTAL &&
        isSeparating

/**
 * Converts window-relative fold bounds into top-video and bottom-controls
 * heights. [foldGuardPx] keeps touch targets away from flexible creases whose
 * reported bounds can have zero height.
 */
internal fun calculateTabletopPlayerPaneLayout(
    rootTopPx: Int,
    rootBottomPx: Int,
    foldTopPx: Int,
    foldBottomPx: Int,
    foldGuardPx: Int,
): TabletopPlayerPaneLayout? {
    val rootHeightPx = rootBottomPx - rootTopPx
    if (rootHeightPx <= 0 || foldGuardPx < 0) return null

    val relativeFoldTopPx = (foldTopPx - rootTopPx).coerceIn(0, rootHeightPx)
    val relativeFoldBottomPx = (foldBottomPx - rootTopPx)
        .coerceIn(relativeFoldTopPx, rootHeightPx)
    val videoHeightPx = (relativeFoldTopPx - foldGuardPx).coerceAtLeast(0)
    val controlsTopPx = (relativeFoldBottomPx + foldGuardPx).coerceAtMost(rootHeightPx)
    val controlsHeightPx = rootHeightPx - controlsTopPx

    return if (videoHeightPx > 0 && controlsHeightPx > 0) {
        TabletopPlayerPaneLayout(
            videoHeightPx = videoHeightPx,
            controlsHeightPx = controlsHeightPx,
        )
    } else {
        null
    }
}

@Composable
internal fun rememberTabletopPlayerPosture(activity: Activity?): TabletopPlayerPosture? {
    val lifecycleOwner = LocalLifecycleOwner.current
    val posture by produceState<TabletopPlayerPosture?>(
        initialValue = null,
        activity,
        lifecycleOwner,
    ) {
        val hostActivity = activity ?: return@produceState
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(hostActivity)
                .windowLayoutInfo(hostActivity)
                .collect { layoutInfo ->
                    value = layoutInfo.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull { feature ->
                            isTabletopPlayerPosture(
                                state = feature.state,
                                orientation = feature.orientation,
                                isSeparating = feature.isSeparating,
                            )
                        }
                        ?.let { feature ->
                            TabletopPlayerPosture(foldBounds = Rect(feature.bounds))
                        }
                }
        }
    }
    return posture
}
