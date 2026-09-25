package org.siloserver.silo.tv.ui.screens.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.tv.cast.RemotePlaybackIdentityManager

/**
 * Tells the room whose profile a phone-launched title is playing under, for
 * six seconds as the player opens: "Playing as Alex" over "From Pixel 8",
 * plus the phone's server when it isn't this TV's. Mirrors tvOS
 * `RemotePlaybackIdentityNotice`. Not focusable; the name is display-only.
 */
@Composable
fun TvRemotePlaybackIdentityNotice(
    contentId: String,
    modifier: Modifier = Modifier,
    identityManager: RemotePlaybackIdentityManager = koinInject(),
    serverRegistry: ServerRegistry = koinInject(),
) {
    val identity = remember(contentId) { identityManager.activeIdentity } ?: return
    var visible by remember(contentId) { mutableStateOf(true) }
    LaunchedEffect(contentId) {
        delay(NOTICE_DURATION_MS)
        visible = false
    }

    val profile = identity.profileName?.trim()?.takeIf { it.isNotEmpty() } ?: "your phone's profile"
    val device = identity.controllerDeviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "your phone"
    val server = identity.serverName?.trim()?.takeIf {
        it.isNotEmpty() &&
            !AndroidServerRegistry.serverIdsMatch(identity.serverId, serverRegistry.activeServerId.value)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Smartphone,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Playing as $profile",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (server != null) "From $device · $server" else "From $device",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val NOTICE_DURATION_MS = 6_000L
