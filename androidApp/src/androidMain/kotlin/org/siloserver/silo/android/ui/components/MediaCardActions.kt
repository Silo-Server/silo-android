package org.siloserver.silo.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bundle of optional callbacks for the long-press context menu on a media card.
 *
 * Mirrors the iOS/tvOS card context menu (see iosApp/iosApp/Components/MediaCard.swift).
 * Callers leave callbacks null to omit the corresponding menu item.
 */
data class MediaCardActions(
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
 * Long-press context menu for [MediaCard] and [BackdropCard]. Renders as a
 * [SiloDropdownMenu] anchored to the card. Each item invokes the
 * matching callback in [actions] then closes the menu.
 */
@Composable
fun MediaCardContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: MediaCardActions,
    isPlayed: Boolean,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
) {
    if (actions.isEmpty) return

    SiloDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        actions.onSetWatched?.let { setWatched ->
            MenuRow(
                text = if (isPlayed) "Mark as Unwatched" else "Mark as Watched",
                icon = if (isPlayed) Icons.Default.VisibilityOff else Icons.Default.Check,
                onClick = {
                    setWatched(!isPlayed)
                    onDismiss()
                },
            )
        }
        actions.onToggleFavorite?.let { toggle ->
            MenuRow(
                text = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                onClick = {
                    toggle(!isFavorite)
                    onDismiss()
                },
            )
        }
        actions.onToggleWatchlist?.let { toggle ->
            MenuRow(
                text = if (isInWatchlist) "Remove from Watchlist" else "Add to Watchlist",
                icon = if (isInWatchlist) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                onClick = {
                    toggle(!isInWatchlist)
                    onDismiss()
                },
            )
        }
        actions.onRemoveFromContinueWatching?.let { remove ->
            MenuRow(
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

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    SiloDropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
