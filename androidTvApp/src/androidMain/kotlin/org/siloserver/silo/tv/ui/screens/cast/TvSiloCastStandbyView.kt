package org.siloserver.silo.tv.ui.screens.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.cast.TvSiloCastReceiver

/**
 * Full-screen takeover while a phone controls the TV with nothing playing:
 * "Ready for <phone>", or, while a profile handoff prepares a title the phone
 * is about to play, "Starting <title>" with a spinner.
 */
@Composable
fun TvSiloCastStandbyView(
    state: TvSiloCastReceiver.StandbyState,
    onDisconnect: () -> Unit,
) {
    val preparing = state.preparing
    val disconnectFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { disconnectFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.padding(horizontal = 120.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .background(Color.White.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (preparing != null) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 5.dp,
                        modifier = Modifier.size(76.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Tv,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(76.dp),
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = when {
                        preparing == null -> "Ready for ${state.controllerLabel}"
                        preparing.title != null -> "Starting ${preparing.title}"
                        else -> "Starting playback"
                    },
                    color = Color.White,
                    fontSize = 52.sp,
                    lineHeight = 58.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (preparing != null) {
                        "From ${state.controllerName?.takeIf { it.isNotBlank() } ?: "your phone"}"
                    } else {
                        state.serverName ?: "Remote control active"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = onDisconnect,
                modifier = Modifier.focusRequester(disconnectFocus),
            ) {
                Text("Disconnect Remote")
            }
        }
    }
}
