package org.siloserver.silo.common.ui.marquee

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.IntOffset

/**
 * A short sideways shake each time [trigger] changes (a rejected password or
 * PIN). The value it starts with doesn't shake, so returning to a screen
 * doesn't replay an old failure. Under reduced motion the error's color
 * carries it alone.
 */
fun Modifier.marqueeShake(trigger: Int): Modifier = composed {
    val reduceMotion = rememberReduceMotion()
    val offset = remember { Animatable(0f) }
    val initial = remember { trigger }
    LaunchedEffect(trigger) {
        if (trigger == initial || reduceMotion) return@LaunchedEffect
        offset.animateTo(
            0f,
            keyframes {
                durationMillis = 370
                -8f at 70
                8f at 140
                -6f at 210
                4f at 280
            },
        )
    }
    offset { IntOffset((offset.value * density).toInt(), 0) }
}
