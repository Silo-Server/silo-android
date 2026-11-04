package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloForeground

/**
 * How a dialog button reads: the safe default, the one recommended action, a
 * destructive one, or a destructive one that stays quiet (red text on the
 * default fill) so a safe [Primary] beside it reads as the default.
 */
enum class SiloDialogActionStyle { Default, Primary, Destructive, DestructiveQuiet }

data class SiloDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val style: SiloDialogActionStyle = SiloDialogActionStyle.Default,
    val enabled: Boolean = true,
    /** Takes focus when the dialog opens, for a keyboard or D-pad (touch never shows it). */
    val focusedByDefault: Boolean = false,
)

/** The dialog card's palette: one raised surface over a dim, Paper and red for actions. */
internal object SiloDialogColors {
    val Surface = Color(0xFF1C1C1F)
    val Stroke = Color.White.copy(alpha = 0.06f)
    val Body = SiloForeground.copy(alpha = 0.62f)
    val DefaultButton = Color.White.copy(alpha = 0.08f)
    val Paper = Color(0xFFEDEDED)
    val Ink = Color(0xFF0B0B0C)
}

/**
 * The one dialog. A 28dp card with an optional tinted icon, a title, a body,
 * and full-width buttons stacked in the order given — the recommended or
 * destructive action first, the safe way out last. Stock M3 puts small text
 * buttons in the bottom corner; these are easy to reach with a thumb and say
 * how dangerous they are by their fill.
 *
 * [content] carries anything beyond a sentence of body copy: text fields, a
 * short list. It scrolls when a small window cannot fit it.
 */
@Composable
fun SiloDialog(
    title: String,
    onDismissRequest: () -> Unit,
    actions: List<SiloDialogAction>,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = SiloForeground,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(28.dp)
        val initialFocus = remember { FocusRequester() }
        Column(
            modifier = modifier
                .padding(horizontal = 22.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(SiloDialogColors.Surface)
                .border(1.dp, SiloDialogColors.Stroke, shape)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                }
            }
            Text(
                text = title,
                color = SiloForeground,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    color = SiloDialogColors.Body,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (content != null) {
                CompositionLocalProvider(
                    LocalContentColor provides SiloDialogColors.Body,
                    LocalTextStyle provides TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                ) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content,
                    )
                }
            }
            if (actions.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions.forEach { action ->
                        SiloDialogButton(action, if (action.focusedByDefault) initialFocus else null)
                    }
                    if (actions.any { it.focusedByDefault && it.enabled }) {
                        LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiloDialogButton(action: SiloDialogAction, focusRequester: FocusRequester?) {
    val (fill, textColor) = when (action.style) {
        SiloDialogActionStyle.Primary -> SiloDialogColors.Paper to SiloDialogColors.Ink
        SiloDialogActionStyle.Destructive -> SiloDestructive to Color.White
        SiloDialogActionStyle.DestructiveQuiet -> SiloDialogColors.DefaultButton to SiloDestructive
        SiloDialogActionStyle.Default -> SiloDialogColors.DefaultButton to SiloForeground
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // Before the fill, so a disabled button dims as a whole, not just its label.
            .alpha(if (action.enabled) 1f else 0.4f)
            .clip(shape)
            .background(fill)
            .then(if (focused) Modifier.border(2.dp, SiloForeground, shape) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                enabled = action.enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = action.onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The one confirmation dialog: a question, what it does, and two buttons.
 *
 * Cancel is the safe choice and sits last, under the action. The action is
 * filled red when it destroys or discards something, Paper otherwise.
 */
@Composable
fun SiloConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    destructive: Boolean = true,
    confirmEnabled: Boolean = true,
    icon: ImageVector? = null,
) {
    SiloDialog(
        title = title,
        message = body,
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = icon,
        iconTint = if (destructive) SiloDestructive else SiloForeground,
        actions = listOf(
            SiloDialogAction(
                label = confirmLabel,
                onClick = onConfirm,
                style = if (destructive) SiloDialogActionStyle.Destructive else SiloDialogActionStyle.Primary,
                enabled = confirmEnabled,
            ),
            SiloDialogAction(label = dismissLabel, onClick = onDismiss),
        ),
    )
}

