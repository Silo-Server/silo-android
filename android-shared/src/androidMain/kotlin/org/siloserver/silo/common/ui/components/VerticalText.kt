package org.siloserver.silo.common.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * Turns horizontal text into a bottom-to-top vertical label. `rotate` alone
 * only changes drawing, so the layout would still reserve the text's full
 * horizontal width; this swaps the measured width and height first.
 */
fun Modifier.verticalText(): Modifier = this
    .layout { measurable, _ ->
        val placeable = measurable.measure(Constraints())
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = (placeable.height - placeable.width) / 2,
                y = (placeable.width - placeable.height) / 2,
            )
        }
    }
    .rotate(-90f)
