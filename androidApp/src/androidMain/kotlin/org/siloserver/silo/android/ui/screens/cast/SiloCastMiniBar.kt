package org.siloserver.silo.android.ui.screens.cast

import org.siloserver.silo.common.i18n.stringResourceWithNames
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.cast.SiloCastArtwork
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.ui.components.ThumbhashImage

/**
 * Persistent Remote Control bar shown above the tab content whenever a TV
 * control session is active and the full remote is dismissed. Mirrors
 * silo-apple's `SiloControlMiniBar`: poster thumb, title, target line
 * ("Playing on", "Starting on", or "Connected / to" while the TV is idle),
 * and a trailing play/pause button (spinner while reconnecting or starting).
 * Tapping reopens the remote; disconnect lives in the remote's menu, not here.
 *
 * Inset handling is the caller's: on tab screens the bar sits in the
 * Scaffold's bottomBar slot directly above the nav menu (which owns the
 * gesture inset); on menu-less routes the caller adds navigationBarsPadding.
 *
 * [inline] is the compact capsule the nav bar shows beside its minimized Home
 * control (iOS's inline tab bar accessory): it fills the slot the caller gives
 * it instead of adding its own margins, and uses a smaller poster.
 * [barModifier] applies to the bar itself, inside its margins, so a caller can
 * animate the visible card between placements. A caller that shows the bar
 * in more than one placement passes [sharedArtwork] resolved once above them, so
 * moving the bar does not drop the loaded title and poster.
 */
@Composable
fun SiloCastMiniBar(
    controller: SiloCastController,
    onOpenRemote: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    barModifier: Modifier = Modifier,
    sharedArtwork: SiloCastArtwork? = null,
) {
    val state by controller.state.collectAsState()
    val playback = state.playbackState
    val launch = state.launch
    val isIdle = playback?.contentId.isNullOrEmpty()
    val targetName = state.connectedTarget?.name ?: SILO_TV_NAME
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
        val artwork = sharedArtwork ?: rememberSiloCastArtwork(launch?.contentId ?: playback?.contentId)
        Surface(
            onClick = onOpenRemote,
            modifier = if (inline) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            }.then(barModifier),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            shape = if (inline) CircleShape else RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = if (inline) {
                    Modifier.padding(start = 14.dp, end = 4.dp)
                } else {
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (inline) 10.dp else 12.dp),
            ) {
                MiniBarThumb(
                    posterUrl = artwork.posterUrl,
                    thumbhash = artwork.posterThumbhash,
                    width = if (inline) 30.dp else 34.dp,
                    height = if (inline) 44.dp else 50.dp,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            state.isReconnecting -> stringResource(CommonR.string.cast_reconnecting)
                            launch != null -> artwork.title ?: stringResource(CommonR.string.cast_startingPlayback)
                            isIdle -> stringResource(CommonR.string.cast_miniBar_connected)
                            else -> playback?.title ?: stringResource(CommonR.string.cast_miniBar_connected)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            state.isReconnecting -> stringResourceWithNames(CommonR.string.cast_miniBar_toTv, targetName)
                            launch != null -> stringResourceWithNames(CommonR.string.cast_startingOn, targetName)
                            isIdle -> stringResourceWithNames(CommonR.string.cast_miniBar_toTv, targetName)
                            else -> stringResourceWithNames(CommonR.string.cast_playingOn, targetName)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (state.isReconnecting || launch != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    // Explicit, for what the bar shows: another controller may
                    // have changed it since.
                    IconButton(onClick = { controller.setPlaying(playback?.isPlaying != true) }) {
                        Icon(
                            if (playback?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (playback?.isPlaying == true) CommonR.string.player_controls_pause else CommonR.string.common_play,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBarThumb(posterUrl: String?, thumbhash: String?, width: Dp, height: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (!posterUrl.isNullOrBlank()) {
            ThumbhashImage(
                url = posterUrl,
                thumbhash = thumbhash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = width, height = height),
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

/** The name a TV goes by before it reports its own; a product name, not translated. */
internal const val SILO_TV_NAME = "Silo TV"
