package org.siloserver.silo.common.player

import android.graphics.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Resize the mounted PlayerView without replacing its surface or player. */
fun Modifier.videoPlayerViewport(viewport: Rect?, parentBounds: Rect?): Modifier = layout { measurable, constraints ->
    if (viewport == null || parentBounds == null) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val placeable = measurable.measure(
            Constraints.fixed(viewport.width().coerceAtLeast(0), viewport.height().coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(viewport.left - parentBounds.left, viewport.top - parentBounds.top)
        }
    }
}

fun LayoutCoordinates.videoViewportBounds(): Rect = positionInWindow().let { position ->
    Rect(
        position.x.roundToInt(),
        position.y.roundToInt(),
        (position.x + size.width).roundToInt(),
        (position.y + size.height).roundToInt(),
    )
}
