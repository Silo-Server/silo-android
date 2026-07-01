package com.continuum.app.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Generic single-field text-input dialog for TV. It uses Silo's own ANSI-style
 * remote keyboard so profile names, PINs, server labels, and one-off dialogs
 * all avoid Android TV's inconsistent platform IME behavior.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvTextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (text: String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    isBusy: Boolean = false,
    errorMessage: String? = null,
    allowBlank: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by remember { mutableStateOf(initialValue) }
    var isKeyboardVisible by remember { mutableStateOf(true) }
    val fieldFocusRequester = remember { FocusRequester() }
    val firstKeyFocusRequester = remember { FocusRequester() }
    val canConfirm = !isBusy && (allowBlank || text.isNotBlank())
    val submit = {
        if (canConfirm) onConfirm(text)
    }

    LaunchedEffect(isKeyboardVisible) {
        delay(120)
        if (isKeyboardVisible) {
            runCatching { firstKeyFocusRequester.requestFocus() }
        } else {
            runCatching { fieldFocusRequester.requestFocus() }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 19.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TvDialogTextDisplayField(
                        value = text,
                        hint = label,
                        enabled = !isBusy,
                        isPassword = keyboardType == KeyboardType.Password ||
                            keyboardType == KeyboardType.NumberPassword,
                        focusRequester = fieldFocusRequester,
                        onOpenKeyboard = { isKeyboardVisible = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    )
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(text = "Cancel", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = submit,
                            enabled = canConfirm,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = if (isBusy) "..." else confirmLabel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            if (isKeyboardVisible) {
                TvAnsiKeyboard(
                    primaryLabel = confirmLabel,
                    primaryEnabled = canConfirm,
                    enabled = !isBusy,
                    firstKeyFocusRequester = firstKeyFocusRequester,
                    onAction = { action ->
                        text = applyTvDialogKeyboardAction(
                            value = text,
                            action = action,
                            keyboardType = keyboardType,
                        )
                    },
                    onPrimary = submit,
                    onDismiss = { isKeyboardVisible = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 48.dp, end = 48.dp, bottom = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun TvDialogTextDisplayField(
    value: String,
    hint: String,
    enabled: Boolean,
    isPassword: Boolean,
    focusRequester: FocusRequester,
    onOpenKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val display = if (value.isBlank()) {
        hint
    } else if (isPassword) {
        "*".repeat(value.length)
    } else {
        value
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (isFocused) 0.07f else 0.045f))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (isFocused) 0.92f else 0.26f),
                shape = shape,
            )
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (shouldOpenTvDialogKeyboard(event)) {
                    onOpenKeyboard()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (shouldOpenTvDialogKeyboard(event)) {
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
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = display,
            color = Color.White.copy(alpha = if (value.isBlank()) 0.56f else 0.96f),
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

private fun shouldOpenTvDialogKeyboard(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val isActivationPress = event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
    if (!isActivationPress) return false
    return when (event.key) {
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        -> true
        else -> event.key.nativeKeyCode in TvDialogActivationKeyCodes
    }
}

private val TvDialogActivationKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

private fun applyTvDialogKeyboardAction(
    value: String,
    action: TvAnsiKeyboardAction,
    keyboardType: KeyboardType,
): String {
    val numericOnly = keyboardType == KeyboardType.Number || keyboardType == KeyboardType.NumberPassword
    return when (action) {
        is TvAnsiKeyboardAction.Insert -> {
            val inserted = if (numericOnly) action.text.filter { it.isDigit() } else action.text
            (value + inserted).take(TV_DIALOG_TEXT_MAX_LENGTH)
        }
        TvAnsiKeyboardAction.Backspace -> value.dropLast(1)
        TvAnsiKeyboardAction.Clear -> ""
    }
}

private const val TV_DIALOG_TEXT_MAX_LENGTH = 200
