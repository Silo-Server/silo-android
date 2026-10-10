package org.siloserver.silo.android.ui.components.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.siloKeyboardActions
import org.siloserver.silo.common.ui.marquee.MarqueeColors

private val LocalMarqueeFieldGrouped = compositionLocalOf { false }

/**
 * Frosted text field with a leading icon. Inside [MarqueeFieldGroup] it drops
 * its own background so the group draws one rounded block with separators.
 */
@Composable
fun MarqueeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    isSecure: Boolean = false,
    showsClearButton: Boolean = false,
    isError: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val grouped = LocalMarqueeFieldGrouped.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(MarqueeMetrics.FieldCorner)
    val textStyle = TextStyle(color = MarqueeColors.Ink, fontSize = MarqueeMetrics.FieldFont)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MarqueeMetrics.FieldHeight)
            .then(
                if (grouped) {
                    if (isError) Modifier.background(MarqueeColors.Error.copy(alpha = 0.08f)) else Modifier
                } else {
                    Modifier
                        .clip(shape)
                        .background(
                            when {
                                isError -> MarqueeColors.Error.copy(alpha = 0.08f)
                                focused -> Color.White.copy(alpha = 0.10f)
                                else -> Color.White.copy(alpha = 0.07f)
                            },
                        )
                        .border(
                            1.dp,
                            when {
                                isError -> MarqueeColors.Error.copy(alpha = 0.65f)
                                focused -> Color.White.copy(alpha = 0.45f)
                                else -> Color.White.copy(alpha = 0.12f)
                            },
                            shape,
                        )
                },
            )
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MarqueeColors.InkTertiary, modifier = Modifier.width(20.dp).size(20.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = textStyle.copy(color = MarqueeColors.InkTertiary), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .semantics { contentDescription = placeholder },
                enabled = enabled,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(Color(0xFF0A84FF)),
                visualTransformation = if (isSecure && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isSecure) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = siloKeyboardActions(onImeAction),
                interactionSource = interaction,
            )
        }
        if (isSecure) {
            Icon(
                imageVector = if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (reveal) "Hide password" else "Show password",
                tint = MarqueeColors.InkTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { reveal = !reveal }
                    .padding(4.dp)
                    .size(20.dp),
            )
        } else if (showsClearButton && value.isNotEmpty() && enabled) {
            Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = "Clear",
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onValueChange("") }
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
    }
}

/** Username and password as one block, like grouped fields on iOS. */
@Composable
fun MarqueeFieldGroup(
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(MarqueeMetrics.FieldCorner)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, if (isError) MarqueeColors.Error.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.12f), shape),
    ) {
        CompositionLocalProvider(LocalMarqueeFieldGrouped provides true) { content() }
    }
}

/** The hairline between rows of a [MarqueeFieldGroup] or another grouped list. */
@Composable
fun MarqueeSeparator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MarqueeColors.Separator))
}
