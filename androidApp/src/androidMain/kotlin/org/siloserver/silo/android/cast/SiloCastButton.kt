package org.siloserver.silo.android.cast

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cast
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.components.SiloDialogActionStyle
import org.siloserver.silo.android.ui.components.SiloDialogAction
import org.siloserver.silo.android.ui.components.SiloDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject

/**
 * Google Cast (Chromecast) button + route picker for the video player top bar.
 *
 * Distinct from the NSD/mDNS SiloCast device-remote button. Tapping it stages
 * the cast-ready stream via [onStartCast] (Tier-2 session) and opens the device
 * picker; the [SiloCastSessionManager] loads the media once a device connects.
 */
@Composable
fun SiloCastButton(
    onStartCast: () -> Unit,
    modifier: Modifier = Modifier,
    castManager: SiloCastSessionManager = koinInject(),
) {
    val castState by castManager.castState.collectAsState()
    val routes by castManager.availableRoutes.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    // Active-scan discovery runs only while the picker is open (battery).
    DisposableEffect(showPicker) {
        if (showPicker) castManager.startDiscovery()
        onDispose { if (showPicker) castManager.stopDiscovery() }
    }

    IconButton(
        onClick = {
            castManager.refreshRoutes()
            showPicker = true
        },
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            imageVector = if (castState.isConnected) Icons.Default.CastConnected else Icons.Default.Cast,
            contentDescription = "Cast",
            tint = if (castState.isConnected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(22.dp),
        )
    }

    if (showPicker) {
        SiloDialog(
            title = "Cast to",
            message = if (routes.isEmpty()) {
                "No cast devices found. Make sure your Chromecast is on the same Wi-Fi network."
            } else {
                null
            },
            onDismissRequest = { showPicker = false },
            icon = Icons.Rounded.Cast,
            actions = buildList {
                if (castState.isConnected) {
                    add(
                        SiloDialogAction(
                            label = "Stop casting",
                            style = SiloDialogActionStyle.Destructive,
                            onClick = {
                                castManager.disconnect()
                                showPicker = false
                            },
                        ),
                    )
                }
                add(SiloDialogAction("Close", { showPicker = false }))
            },
        ) {
            if (routes.isNotEmpty()) {
                Column {
                    routes.forEach { route ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (route.isSelected) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                                .clickable {
                                    // Stage the cast stream, then join the route.
                                    onStartCast()
                                    castManager.selectRoute(route.id)
                                    showPicker = false
                                }
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tv,
                                contentDescription = null,
                                tint = SiloForeground.copy(alpha = 0.78f),
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(text = route.name, color = SiloForeground, fontSize = 15.sp)
                                if (route.isConnecting) {
                                    Text(text = "Connecting…", fontSize = 12.sp)
                                }
                            }
                            if (route.isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = SiloForeground,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
