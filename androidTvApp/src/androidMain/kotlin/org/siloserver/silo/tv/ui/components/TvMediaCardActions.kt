package org.siloserver.silo.tv.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent

/**
 * Bundle of optional callbacks for the TV long-press / DPAD-center-hold
 * context menu on a media card. Mirrors
 * [org.siloserver.silo.android.ui.components.MediaCardActions] from the phone
 * app — kept separate to avoid pulling phone components into the TV module.
 */
data class TvMediaCardActions(
    val onPlay: (() -> Unit)? = null,
    val playLabel: String = "Play",
    val onSetWatched: ((watched: Boolean) -> Unit)? = null,
    val onToggleFavorite: ((favorite: Boolean) -> Unit)? = null,
    val onToggleWatchlist: ((inWatchlist: Boolean) -> Unit)? = null,
    val onRemoveFromContinueWatching: (() -> Unit)? = null,
) {
    val isEmpty: Boolean
        get() = onPlay == null &&
            onSetWatched == null &&
            onToggleFavorite == null &&
            onToggleWatchlist == null &&
            onRemoveFromContinueWatching == null
}

/**
 * Long-press context menu rendered as a focusable TV popup. The DPAD captures focus inside the popup, so users can
 * navigate the actions with the d-pad and press DPAD_CENTER to select.
 */
@Composable
fun TvMediaCardContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: TvMediaCardActions,
    isPlayed: Boolean,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
) {
    // Nothing to compose while closed — this is called for every card in
    // every rail, so the requester/position-provider allocations below must
    // not run for the (almost always) collapsed case.
    if (actions.isEmpty || !expanded) return

    // A TV Card reports its long-click while DPAD_CENTER is still held. The
    // popup immediately focuses its first row, so the matching key-up would
    // otherwise click that row as though it were a fresh selection. Swallow
    // the remainder of the opening press, then arm the menu for the next one.
    var awaitingOpeningPressRelease by remember(expanded) { mutableStateOf(expanded) }
    val firstRowFocus = remember { FocusRequester() }
    val popupMarginPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val popupPositionProvider = remember(popupMarginPx) {
        AboveAnchorPopupPositionProvider(popupMarginPx)
    }
    val firstAction = when {
        actions.onPlay != null -> TvMenuAction.Play
        actions.onSetWatched != null -> TvMenuAction.Watched
        actions.onToggleFavorite != null -> TvMenuAction.Favorite
        actions.onToggleWatchlist != null -> TvMenuAction.Watchlist
        else -> TvMenuAction.RemoveFromContinueWatching
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.dp),
    ) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = false,
            ),
        ) {
            Column(
                modifier = Modifier
                    .width(276.dp)
                    .background(DarkSurfaceElevated, RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                    .padding(8.dp)
                    .onPreviewKeyEvent { event ->
                        if (!awaitingOpeningPressRelease || event.key !in MenuSelectKeys) {
                            false
                        } else {
                            if (event.type == KeyEventType.KeyUp) {
                                awaitingOpeningPressRelease = false
                            }
                            true
                        }
                    }
                    .then(rememberTvDialogInitialFocus(firstRowFocus)),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                TvMediaCardMenuRows(
                    actions = actions,
                    isPlayed = isPlayed,
                    isFavorite = isFavorite,
                    isInWatchlist = isInWatchlist,
                    firstAction = firstAction,
                    firstRowFocus = firstRowFocus,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun TvMediaCardMenuRows(
    actions: TvMediaCardActions,
    isPlayed: Boolean,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    firstAction: TvMenuAction,
    firstRowFocus: FocusRequester,
    onDismiss: () -> Unit,
) {
    actions.onPlay?.let { play ->
        TvMenuRow(
            text = actions.playLabel,
            icon = Icons.Default.PlayArrow,
            onClick = {
                onDismiss()
                play()
            },
            modifier = if (firstAction == TvMenuAction.Play) {
                Modifier.focusRequester(firstRowFocus)
            } else {
                Modifier
            },
        )
    }
    actions.onSetWatched?.let { setWatched ->
        TvMenuRow(
            text = if (isPlayed) "Mark as Unwatched" else "Mark as Watched",
            icon = if (isPlayed) Icons.Default.VisibilityOff else Icons.Default.Check,
            onClick = {
                setWatched(!isPlayed)
                onDismiss()
            },
            modifier = if (firstAction == TvMenuAction.Watched) {
                Modifier.focusRequester(firstRowFocus)
            } else {
                Modifier
            },
        )
    }
    actions.onToggleFavorite?.let { toggle ->
        TvMenuRow(
            text = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            onClick = {
                toggle(!isFavorite)
                onDismiss()
            },
            modifier = if (firstAction == TvMenuAction.Favorite) {
                Modifier.focusRequester(firstRowFocus)
            } else {
                Modifier
            },
        )
    }
    actions.onToggleWatchlist?.let { toggle ->
        TvMenuRow(
            text = if (isInWatchlist) "Remove from Watchlist" else "Add to Watchlist",
            icon = if (isInWatchlist) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
            onClick = {
                toggle(!isInWatchlist)
                onDismiss()
            },
            modifier = if (firstAction == TvMenuAction.Watchlist) {
                Modifier.focusRequester(firstRowFocus)
            } else {
                Modifier
            },
        )
    }
    actions.onRemoveFromContinueWatching?.let { remove ->
        TvMenuRow(
            text = "Remove from Continue Watching",
            icon = Icons.Default.Close,
            onClick = {
                remove()
                onDismiss()
            },
            modifier = if (firstAction == TvMenuAction.RemoveFromContinueWatching) {
                Modifier.focusRequester(firstRowFocus)
            } else {
                Modifier
            },
        )
    }
}

private val MenuSelectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

private class AboveAnchorPopupPositionProvider(
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val x = centeredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val aboveY = anchorBounds.top - popupContentSize.height - marginPx
        val y = if (aboveY >= 0) {
            aboveY
        } else {
            (anchorBounds.bottom + marginPx)
                .coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}

private enum class TvMenuAction {
    Play,
    Watched,
    Favorite,
    Watchlist,
    RemoveFromContinueWatching,
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMenuRow(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(1.5.dp, Color.White),
                shape = shape,
            ),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(Color.White.copy(alpha = 0.14f), 10.dp),
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
                color = if (isFocused) FocusedContent else Color.White,
            )
        }
    }
}
