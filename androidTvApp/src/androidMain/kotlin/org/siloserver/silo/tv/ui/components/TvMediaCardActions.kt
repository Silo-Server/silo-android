package org.siloserver.silo.tv.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Bundle of optional callbacks for the TV long-press / DPAD-center-hold
 * context menu on a media card. Mirrors
 * [org.siloserver.silo.android.ui.components.MediaCardActions] from the phone
 * app — kept separate to avoid pulling phone components into the TV module.
 */
data class TvMediaCardActions(
    val onSetWatched: ((watched: Boolean) -> Unit)? = null,
    val onToggleFavorite: ((favorite: Boolean) -> Unit)? = null,
    val onToggleWatchlist: ((inWatchlist: Boolean) -> Unit)? = null,
    val onRemoveFromContinueWatching: (() -> Unit)? = null,
) {
    val isEmpty: Boolean
        get() = onSetWatched == null &&
            onToggleFavorite == null &&
            onToggleWatchlist == null &&
            onRemoveFromContinueWatching == null
}

/**
 * Long-press context menu rendered as a Material 3 DropdownMenu anchored to
 * a focused TV card. The DPAD captures focus inside the popup, so users can
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
    if (actions.isEmpty) return

    // A TV Card reports its long-click while DPAD_CENTER is still held. The
    // popup immediately focuses its first row, so the matching key-up would
    // otherwise click that row as though it were a fresh selection. Swallow
    // the remainder of the opening press, then arm the menu for the next one.
    var awaitingOpeningPressRelease by remember(expanded) { mutableStateOf(expanded) }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (!awaitingOpeningPressRelease || event.key !in MenuSelectKeys) {
                false
            } else {
                if (event.type == KeyEventType.KeyUp) {
                    awaitingOpeningPressRelease = false
                }
                true
            }
        },
    ) {
        actions.onSetWatched?.let { setWatched ->
            TvMenuRow(
                text = if (isPlayed) "Mark as Unwatched" else "Mark as Watched",
                icon = if (isPlayed) Icons.Default.VisibilityOff else Icons.Default.Check,
                onClick = {
                    setWatched(!isPlayed)
                    onDismiss()
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
            )
        }
    }
}

private val MenuSelectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

@Composable
private fun TvMenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
