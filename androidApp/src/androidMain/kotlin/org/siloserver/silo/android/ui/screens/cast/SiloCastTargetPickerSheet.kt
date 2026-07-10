package org.siloserver.silo.android.ui.screens.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.cast.SiloCastLaunchRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiloCastTargetPickerSheet(
    launchRequest: SiloCastLaunchRequest,
    onDismiss: () -> Unit,
    controller: SiloCastController = koinInject(),
) {
    val state by controller.state.collectAsState()

    LaunchedEffect(controller) {
        controller.startBrowsing()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Cast, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Play on device", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose an Android TV on this network.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (state.targets.isEmpty()) {
                Text(
                    text = if (state.isConnecting) "Connecting..." else "Searching...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                state.targets.forEach { target ->
                    val supportsIdentityHandoff = target.version >= 2
                    ListItem(
                        headlineContent = { Text(target.name) },
                        supportingContent = {
                            Text(
                                when {
                                    !supportsIdentityHandoff ->
                                        "Update Silo on this TV to use your profile."
                                    target.serverId != launchRequest.serverId ->
                                        "Temporarily use your phone's server and profile"
                                    else -> "Use your phone profile"
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.Cast, contentDescription = null) },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    controller.launchOnTarget(target, launchRequest)
                                    onDismiss()
                                },
                                enabled = supportsIdentityHandoff,
                            ) {
                                Text("Play")
                            }
                        },
                    )
                }
            }

            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
