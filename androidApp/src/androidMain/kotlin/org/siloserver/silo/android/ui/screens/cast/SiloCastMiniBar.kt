package org.siloserver.silo.android.ui.screens.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.common.ui.components.ThumbhashImage

/**
 * Persistent "Playing on <TV>" bar shown above the tab content whenever a TV
 * control session is active and the full remote is dismissed. Mirrors
 * silo-apple's `SiloControlMiniBar`: poster thumb, title, target line, and a
 * trailing play/pause button (spinner while reconnecting). Tapping reopens
 * the remote; disconnect lives in the remote's menu, not here.
 *
 * Inset handling is the caller's: on tab screens the bar sits in the
 * Scaffold's bottomBar slot directly above the nav menu (which owns the
 * gesture inset); on menu-less routes the caller adds navigationBarsPadding.
 */
@Composable
fun SiloCastMiniBar(
    controller: SiloCastController,
    onOpenRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val playback = state.playbackState
    val targetName = state.connectedTarget?.name ?: "Silo TV"
    // Keep the bar up through a reconnect (with a spinner) instead of having
    // it vanish and pop back; hide it while an auto-resume probe is still
    // unconfirmed so idle TVs never surface a phantom session.
    val visible = (state.hasActiveSession && !state.isAutoResuming) || state.isReconnecting

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        val artwork = rememberSiloCastArtwork(playback?.contentId)
        Surface(
            onClick = onOpenRemote,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiniBarThumb(posterUrl = artwork.posterUrl, thumbhash = artwork.posterThumbhash)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.isReconnecting) "Reconnecting…" else (playback?.title ?: "Connected"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (state.isReconnecting) "to $targetName" else "Playing on $targetName",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (state.isReconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = { controller.playPause() }) {
                        Icon(
                            if (playback?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playback?.isPlaying == true) "Pause" else "Play",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBarThumb(posterUrl: String?, thumbhash: String?) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 34.dp, height = 50.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (!posterUrl.isNullOrBlank()) {
            ThumbhashImage(
                url = posterUrl,
                thumbhash = thumbhash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 34.dp, height = 50.dp),
            )
        } else {
            Icon(
                Icons.Outlined.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
