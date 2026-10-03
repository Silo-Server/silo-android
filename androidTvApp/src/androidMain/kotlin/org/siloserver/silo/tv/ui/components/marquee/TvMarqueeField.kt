package org.siloserver.silo.tv.ui.components.marquee

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.rememberReduceMotion
import org.siloserver.silo.tv.ui.components.tvShowImeOnSelect

/**
 * TV text field in the first-run look: glass at rest, white with black ink
 * when focused. SELECT opens the keyboard ([tvShowImeOnSelect]), so focus
 * moving through the form never raises it by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvMarqueeField(
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    isSecure: Boolean = false,
    /** With [isSecure], shows the characters instead of dots. */
    revealed: Boolean = false,
    isError: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Text is typed on the keyboard; without it up, Left and Right move
    // between controls (the show-password button beside a field) rather
    // than the invisible cursor.
    val imeVisible = WindowInsets.isImeVisible
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(if (focused && !reduceMotion) 1.04f else 1f, label = "tvMarqueeFieldScale")
    val shape = RoundedCornerShape(TvMarqueeMetrics.FieldCorner)
    val ink = if (focused) Color.Black else MarqueeColors.Ink
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = ink, fontSize = TvMarqueeMetrics.FieldFont),
        cursorBrush = SolidColor(if (focused) Color.Black else MarqueeColors.Ink),
        visualTransformation = if (isSecure && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isSecure) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .height(TvMarqueeMetrics.FieldHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(if (focused) MarqueeColors.Ink else Color.White.copy(alpha = if (isError) 0.06f else 0.08f), shape)
            .border(
                width = if (isError) 1.5.dp else 1.dp,
                color = when {
                    isError -> MarqueeColors.Error.copy(alpha = 0.75f)
                    focused -> Color.Transparent
                    else -> Color.White.copy(alpha = 0.12f)
                },
                shape = shape,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .onPreviewKeyEvent { event ->
                if (imeVisible || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    else -> false
                }
            }
            .tvShowImeOnSelect()
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (focused) Color.Black.copy(alpha = 0.45f) else MarqueeColors.InkTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = if (focused) Color.Black.copy(alpha = 0.4f) else MarqueeColors.InkTertiary,
                            fontSize = TvMarqueeMetrics.FieldFont,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            }
        },
    )
}
