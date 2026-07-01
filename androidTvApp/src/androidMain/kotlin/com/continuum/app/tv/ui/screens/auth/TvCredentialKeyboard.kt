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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.continuum.app.tv.ui.components.AuroraAccent
import com.continuum.app.tv.ui.components.TvAnsiKeyboard
import com.continuum.app.tv.ui.components.TvAnsiKeyboardAction

@Composable
internal fun CredentialDisplayField(
    value: String,
    hint: String,
    enabled: Boolean,
    isActive: Boolean,
    isPassword: Boolean,
    passwordVisible: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onOpenKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isEngaged = isFocused || isActive
    val display = credentialDisplayValue(
        value = value,
        isPassword = isPassword,
        passwordVisible = passwordVisible,
    ).ifBlank { hint }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (isEngaged) 0.07f else 0.045f))
            .border(
                width = if (isEngaged) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (isEngaged) 0.92f else 0.26f),
                shape = RoundedCornerShape(8.dp),
            )
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (shouldOpenCredentialKeyboard(event)) {
                    onOpenKeyboard()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                if (shouldOpenCredentialKeyboard(event)) {
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
        if (isEngaged && enabled) {
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

private fun shouldOpenCredentialKeyboard(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val isActivationPress = event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
    if (!isActivationPress) return false
    return when (event.key) {
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        -> true
        else -> event.key.nativeKeyCode in TvCredentialActivationKeyCodes
    }
}

private val TvCredentialActivationKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

@Composable
internal fun CredentialVisibilityButton(
    passwordVisible: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (isFocused) 0.94f else 0.07f))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (isFocused) 0.96f else 0.18f),
                shape = shape,
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = if (passwordVisible) "Hide password" else "Show password",
            tint = if (isFocused) Color.Black.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.84f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun SiloCredentialKeyboard(
    field: TvCredentialField,
    username: String,
    password: String,
    passwordVisible: Boolean,
    enabled: Boolean,
    firstKeyFocusRequester: FocusRequester,
    onAction: (TvCredentialKeyboardAction) -> Unit,
    onPrimary: () -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryLabel = if (field == TvCredentialField.Username) "Next" else "Sign In"
    val primaryEnabled = when (field) {
        TvCredentialField.Username -> enabled
        TvCredentialField.Password -> canSubmitTvCredentialLogin(username, password, isLoading = !enabled)
    }
    TvAnsiKeyboard(
        primaryLabel = primaryLabel,
        primaryEnabled = primaryEnabled,
        enabled = enabled,
        firstKeyFocusRequester = firstKeyFocusRequester,
        onAction = { action -> onAction(action.toCredentialAction()) },
        onPrimary = onPrimary,
        onDismiss = onDismiss,
        modifier = modifier,
        showPasswordVisibilityKey = field == TvCredentialField.Password,
        passwordVisible = passwordVisible,
        onTogglePasswordVisibility = onTogglePasswordVisibility,
    )
}

private fun TvAnsiKeyboardAction.toCredentialAction(): TvCredentialKeyboardAction =
    when (this) {
        is TvAnsiKeyboardAction.Insert -> TvCredentialKeyboardAction.Insert(text)
        TvAnsiKeyboardAction.Backspace -> TvCredentialKeyboardAction.Backspace
        TvAnsiKeyboardAction.Clear -> TvCredentialKeyboardAction.Clear
    }
