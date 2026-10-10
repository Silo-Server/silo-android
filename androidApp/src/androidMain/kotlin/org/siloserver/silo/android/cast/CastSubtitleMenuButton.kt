package org.siloserver.silo.android.cast

import org.siloserver.silo.android.ui.components.SiloDropdownMenuItem
import org.siloserver.silo.android.ui.components.SiloDropdownMenu
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phone-side CC picker for a live Google Cast session. The receiver (the TV
 * screen) can't host our UI, so language choice happens here: a CC button that
 * opens a menu of the declared receiver text tracks plus "Off". Renders
 * nothing when the loaded media declares no text tracks.
 */
@Composable
fun CastSubtitleMenuButton(
    options: List<CastSubtitleOption>,
    activeId: Long?,
    onSelect: (Long?) -> Unit,
    tint: Color = Color.Unspecified,
    iconSize: Dp = 24.dp,
) {
    if (options.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }

    IconButton(onClick = { menuOpen = true }) {
        Icon(
            imageVector = if (activeId != null) {
                Icons.Default.ClosedCaption
            } else {
                Icons.Outlined.ClosedCaptionOff
            },
            contentDescription = "Subtitles",
            tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
            modifier = Modifier.size(iconSize),
        )
        SiloDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            SiloDropdownMenuItem(
                text = { Text("Off") },
                leadingIcon = {
                    if (activeId == null) Icon(Icons.Default.Check, contentDescription = null)
                },
                onClick = {
                    menuOpen = false
                    onSelect(null)
                },
            )
            options.forEach { option ->
                SiloDropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        if (activeId == option.id) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(option.id)
                    },
                )
            }
        }
    }
}
