package com.continuum.app.tv.ui.screens.search

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.continuum.app.tv.ui.components.AuroraAccent
import com.continuum.app.tv.ui.components.TvAnsiKeyboard
import com.continuum.app.tv.ui.components.TvAnsiKeyboardAction
import com.continuum.app.tv.ui.theme.ElevatedSurface

internal const val TV_SEARCH_QUERY_MAX_LENGTH = 200

@Composable
internal fun SearchDisplayField(
    value: String,
    hint: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onOpenKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val display = value.ifBlank { hint }
    val shape = RoundedCornerShape(9.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(ElevatedSurface)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (isFocused) 0.34f else 0.16f),
                shape = shape,
            )
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (shouldOpenSearchKeyboard(event)) {
                    onOpenKeyboard()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (shouldOpenSearchKeyboard(event)) {
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
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (isFocused) 0.10f else 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            M3Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(14.dp),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = display,
            color = Color.White.copy(alpha = if (value.isBlank()) 0.56f else 0.96f),
            fontSize = 16.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (isFocused && enabled) {
            Box(
                modifier = Modifier
                    .height(18.dp)
                    .padding(start = 8.dp)
                    .background(AuroraAccent.copy(alpha = 0.92f))
                    .widthIn(min = 2.dp, max = 2.dp),
            )
        }
    }
}

private fun shouldOpenSearchKeyboard(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val isActivationPress = event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
    if (!isActivationPress) return false
    return when (event.key) {
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        -> true
        else -> event.key.nativeKeyCode in TvSearchActivationKeyCodes
    }
}

private val TvSearchActivationKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

@Composable
internal fun SiloSearchKeyboard(
    value: String,
    enabled: Boolean,
    firstKeyFocusRequester: FocusRequester,
    onAction: (TvAnsiKeyboardAction) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvAnsiKeyboard(
        primaryLabel = "Search",
        primaryEnabled = value.isNotBlank() && enabled,
        enabled = enabled,
        firstKeyFocusRequester = firstKeyFocusRequester,
        onAction = onAction,
        onPrimary = onSearch,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}
