package com.continuum.app.tv.ui.screens.auth

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.continuum.app.tv.ui.components.AuroraAccent
import com.continuum.app.tv.ui.components.TvAnsiKeyboard
import com.continuum.app.tv.ui.components.TvAnsiKeyboardAction
import com.continuum.app.tv.ui.components.TvAnsiMainKey
import com.continuum.app.tv.ui.components.TvAnsiShortcutKey

@Composable
internal fun ServerUrlDisplayField(
    value: String,
    hint: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onOpenKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val display = value.ifBlank { hint }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (isFocused) 0.07f else 0.045f))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (isFocused) 0.92f else 0.26f),
                shape = RoundedCornerShape(8.dp),
            )
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (shouldOpenServerUrlKeyboard(event)) {
                    onOpenKeyboard()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                if (shouldOpenServerUrlKeyboard(event)) {
                    onOpenKeyboard()
                    true
                } else {
                    false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpenKeyboard,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = display,
            color = Color.White.copy(alpha = if (value.isBlank()) 0.56f else 0.96f),
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isFocused && enabled) {
            Box(
                modifier = Modifier
                    .height(22.dp)
                    .padding(start = 8.dp)
                    .background(AuroraAccent.copy(alpha = 0.92f))
                    .widthIn(min = 2.dp, max = 2.dp),
            )
        }
    }
}

private fun shouldOpenServerUrlKeyboard(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val isActivationPress = event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
    if (!isActivationPress) return false
    return when (event.key) {
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        -> true
        else -> event.key.nativeKeyCode in TvServerUrlActivationKeyCodes
    }
}

private val TvServerUrlActivationKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

@Composable
internal fun SiloServerUrlKeyboard(
    value: String,
    enabled: Boolean,
    firstKeyFocusRequester: FocusRequester,
    onAction: (TvServerUrlKeyboardAction) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectEnabled = canSubmitTvServerUrl(value, isLoading = !enabled)
    TvAnsiKeyboard(
        primaryLabel = "Connect",
        primaryEnabled = connectEnabled,
        enabled = enabled,
        firstKeyFocusRequester = firstKeyFocusRequester,
        onAction = { action -> onAction(action.toServerUrlAction()) },
        onPrimary = onConnect,
        onDismiss = onDismiss,
        modifier = modifier,
        shortcuts = ServerUrlShortcuts,
        mainKeys = ServerUrlMainKeys,
    )
}

private val ServerUrlShortcuts = listOf(
    TvAnsiShortcutKey("https://", weight = 1.65f),
    TvAnsiShortcutKey("http://", weight = 1.5f),
    TvAnsiShortcutKey(".com", weight = 1.25f),
)

private val ServerUrlMainKeys = listOf(
    TvAnsiMainKey(":", weight = 0.9f),
)

private fun TvAnsiKeyboardAction.toServerUrlAction(): TvServerUrlKeyboardAction =
    when (this) {
        is TvAnsiKeyboardAction.Insert -> TvServerUrlKeyboardAction.Insert(text)
        TvAnsiKeyboardAction.Backspace -> TvServerUrlKeyboardAction.Backspace
        TvAnsiKeyboardAction.Clear -> TvServerUrlKeyboardAction.Clear
    }
