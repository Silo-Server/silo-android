package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.screens.player.TvPlayerChrome
import org.siloserver.silo.tv.ui.screens.player.TvPlayerType

data class TvDialogOption(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /**
     * Marks an action ("Mark as watched") rather than a choice. It takes the
     * leading slot where a choice shows its check: a check means "selected".
     */
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvOptionDialog(
    title: String,
    options: List<TvDialogOption>,
    onDismiss: () -> Unit,
    /** The option to focus first, when it is enabled; otherwise the selected or first enabled one. */
    initialFocusKey: String? = null,
) {
    val firstRowFocus = remember { FocusRequester() }
    val focusedKey = initialFocusKey?.takeIf { key -> options.any { it.key == key && it.enabled } }
        ?: options.firstOrNull { it.selected && it.enabled }?.key
        ?: options.firstOrNull { it.enabled }?.key
    val focusedIndex = options.indexOfFirst { it.key == focusedKey }
    val listState: LazyListState = rememberLazyListState()
    val popupPositionProvider = remember { TvOptionDialogWindowPositionProvider() }

    // Re-target focus when the dialog is reused for a new menu (title) or the
    // selected option changes; the shared helper below covers the initial grab.
    // Scroll the target row into view first — a selection beyond the list
    // viewport isn't composed yet, so requesting focus on it would fail.
    LaunchedEffect(title, focusedKey) {
        if (focusedIndex >= 0) {
            runCatching { listState.scrollToItem(focusedIndex) }
        }
        runCatching { firstRowFocus.requestFocus() }
    }

    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 36.dp, top = 60.dp, end = 36.dp, bottom = 42.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Opaque: a translucent panel let the page behind it (the
            // synopsis, a Continue Watching card) read through the header.
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .tvDialogSurface(RoundedCornerShape(20.dp))
                    .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)
                    .then(rememberTvDialogInitialFocus(firstRowFocus)),
            ) {
                Text(
                    text = title.uppercase(),
                    style = TvPlayerType.Eyebrow,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                ) {
                    items(
                        options,
                        key = { it.key },
                        contentType = { "dialog-option" },
                    ) { option ->
                        TvOptionDialogRow(
                            title = option.title,
                            subtitle = option.subtitle,
                            selected = option.selected,
                            enabled = option.enabled,
                            icon = option.icon,
                            onClick = option.onClick,
                            modifier = if (option.key == focusedKey) {
                                Modifier.focusRequester(firstRowFocus)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

internal class TvOptionDialogWindowPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
        y = ((windowSize.height - popupContentSize.height) / 2).coerceAtLeast(0),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvOptionDialogRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val content = when {
        isFocused -> TvPlayerChrome.Ink
        enabled -> TvPlayerChrome.Paper
        else -> TvPlayerChrome.Faint
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            // The current choice keeps a soft fill at rest; focus inverts to Paper.
            containerColor = if (selected) TvPlayerChrome.Selected else Color.Transparent,
            contentColor = content,
            focusedContainerColor = TvPlayerChrome.Paper,
            focusedContentColor = TvPlayerChrome.Ink,
            pressedContainerColor = TvPlayerChrome.Paper,
            pressedContentColor = TvPlayerChrome.Ink,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = TvPlayerChrome.Faint,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Leading slot, kept even when empty so titles line up: a check
            // for the current choice, an icon for an action.
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                val leadingIcon = icon ?: if (selected) Icons.Rounded.Check else null
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = TvPlayerType.Row, color = content)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = TvPlayerType.RowDetail,
                        color = if (isFocused) TvPlayerChrome.InkMuted else TvPlayerChrome.Graphite,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
