package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.cast.RemotePlaybackIdentity

@Composable
internal fun RemotePlaybackIdentityNotice(identity: RemotePlaybackIdentity) {
    val profile = identity.profileName?.trim()?.takeIf { it.isNotEmpty() }
        ?: "your phone's profile"
    val device = identity.controllerDeviceName?.trim()?.takeIf { it.isNotEmpty() }
        ?: "your phone"
    val server = identity.serverName?.trim()?.takeIf { it.isNotEmpty() }
    val source = if (identity.usesDifferentServer && server != null) {
        "From $device · $server"
    } else {
        "From $device"
    }

    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(
                color = Color.Black.copy(alpha = 0.84f),
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Playing as $profile",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = source,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
