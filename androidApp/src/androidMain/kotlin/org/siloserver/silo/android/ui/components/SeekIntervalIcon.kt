package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Material's numbered replay/forward glyph for [seconds], when one exists. */
fun numberedSeekIcon(forward: Boolean, seconds: Int): ImageVector? = when (seconds) {
    5 -> if (forward) Icons.Filled.Forward5 else Icons.Filled.Replay5
    10 -> if (forward) Icons.Filled.Forward10 else Icons.Filled.Replay10
    30 -> if (forward) Icons.Filled.Forward30 else Icons.Filled.Replay30
    else -> null
}

/**
 * Relative-seek glyph that always shows the interval the action will use.
 * Material only ships numbered icons for 5, 10 and 30 seconds; any other
 * configured interval (15, 45, 60, 90) draws the plain circular arrow with the
 * number laid over it in the same position.
 */
@Composable
fun SeekIntervalIcon(
    forward: Boolean,
    seconds: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val numbered = numberedSeekIcon(forward, seconds)
    if (numbered != null) {
        Icon(imageVector = numbered, contentDescription = contentDescription, modifier = modifier, tint = tint)
        return
    }
    BoxWithConstraints(
        modifier = modifier
            .defaultMinSize(24.dp, 24.dp)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        // Sized from the glyph box, not the user's font scale: the number has
        // to fit inside the arrow like Material's numbered icons.
        val numberSize = with(LocalDensity.current) { (side * 0.27f).toSp() }
        Icon(
            imageVector = Icons.Filled.Replay,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { if (forward) scaleX = -1f },
        )
        Box(modifier = Modifier.offset(y = side * 0.06f)) {
            Text(
                text = seconds.toString(),
                color = tint,
                fontWeight = FontWeight.Bold,
                fontSize = numberSize,
                lineHeight = numberSize,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
