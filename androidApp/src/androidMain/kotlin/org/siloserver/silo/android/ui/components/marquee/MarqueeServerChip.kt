package org.siloserver.silo.android.ui.components.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeServerMark

/**
 * Compact server capsule for the top bar. Tapping it opens a menu headed by
 * the server's address with one action, [actionLabel].
 */
@Composable
fun MarqueeServerChip(
    name: String,
    hostLabel: String,
    markUrl: String?,
    actionLabel: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box {
        Row(
            modifier = Modifier
                .height(36.dp)
                .widthIn(max = 220.dp)
                .clip(CircleShape)
                .background(MarqueeColors.GlassFill)
                .border(1.dp, MarqueeColors.Hairline, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null) {
                    expanded = true
                }
                .marqueePressHaptic(interaction)
                .alpha(if (enabled) 1f else 0.45f)
                .semantics { contentDescription = "$name, server. Double-tap to change." }
                .padding(start = 6.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarqueeServerMark(name = name, imageUrl = markUrl, size = 24.dp)
            Text(
                name,
                color = MarqueeColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MarqueeColors.InkTertiary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color(0xFF1C1C1E),
        ) {
            Text(
                hostLabel,
                color = MarqueeColors.InkTertiary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(actionLabel, color = MarqueeColors.Ink) },
                leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null, tint = MarqueeColors.Ink) },
                onClick = {
                    expanded = false
                    onAction()
                },
            )
        }
    }
}
