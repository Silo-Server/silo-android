package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown when a pairing link belongs to a server the user has, but is not
 * currently using.
 *
 * The alternative — looking the code up against whichever server is active —
 * reports a perfectly valid request as invalid or expired, which is a confusing
 * dead end. So the request is still delivered, and the mismatch is stated with
 * the one action that resolves it.
 */
@Composable
fun DevicePairingWrongServerScreen(
    serverName: String,
    onSwitch: () -> Unit,
    onCancel: () -> Unit,
) {
    DevicePairingNoticeStage(
        title = "Different server",
        body = "This TV is signing in to $serverName. Switch to it to continue.",
        primaryLabel = "Switch to $serverName",
        onPrimary = onSwitch,
        onCancel = onCancel,
    )
}

/** Shown when a pairing link names a server the user has not added. */
@Composable
fun DevicePairingUnknownServerScreen(
    origin: String,
    onAddServer: () -> Unit,
    onCancel: () -> Unit,
) {
    DevicePairingNoticeStage(
        title = "Unknown server",
        body = "This TV is signing in to $origin, which isn't one of your servers. " +
            "Add it to continue.",
        primaryLabel = "Add server",
        onPrimary = onAddServer,
        onCancel = onCancel,
    )
}

@Composable
private fun DevicePairingNoticeStage(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
) {
    AuthStage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SiloLogo()

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = AuthColors.OnBackground,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = AuthColors.OnSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AuthColors.Primary),
            ) {
                Text(text = primaryLabel)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Cancel")
            }
        }
    }
}
