package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.auth.openNativeSignIn
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.theme.SiloMutedText
import org.siloserver.silo.android.ui.theme.SiloSurfaceContainer
import org.siloserver.silo.model.auth.AccountIdentity

/**
 * Account settings "Sign-in": the external provider identity linked to this
 * account, Connect (after re-entering the account password) and Disconnect.
 * Hidden on servers without external sign-in.
 */
@Composable
fun SignInSection(
    modifier: Modifier = Modifier,
    viewModel: SignInSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state.browserLaunch) {
        val url = state.browserLaunch ?: return@LaunchedEffect
        viewModel.onBrowserLaunchHandled(context.openNativeSignIn(url))
    }
    if (!state.visible) return

    SettingsSection(title = "Sign-in", modifier = modifier) {
        state.identities.forEach { identity ->
            SettingsRow(
                label = identity.providerName.ifBlank { "Sign-in provider (turned off)" },
                description = identityDescription(identity),
            )
            // The server says up front when this is the only way to sign in.
            if (state.canUnlink != false) {
                SettingsDestructiveRow(
                    label = "Disconnect",
                    description = "Stop signing in to this account with ${SignInSettingsViewModel.providerLabel(identity)}.",
                    onClick = { viewModel.onDisconnect(identity) },
                    enabled = !state.busy,
                )
            }
        }
        if (state.canUnlink == false) {
            SettingsProse(body = SignInSettingsViewModel.ONLY_SIGN_IN_METHOD)
        }
        state.connectable.forEach { provider ->
            SettingsNavigationRow(
                label = "Connect ${provider.displayName}",
                description = SignInSettingsViewModel.connectDescription(provider.displayName),
                onClick = { viewModel.onConnect(provider) },
                enabled = !state.busy,
            )
        }
        state.directory?.let { provider ->
            SettingsNavigationRow(
                label = "Connect ${provider.displayName}",
                description = SignInSettingsViewModel.connectDirectoryDescription(provider.displayName),
                onClick = { viewModel.onConnectDirectory(provider) },
                enabled = !state.busy,
            )
        }
        state.network?.let { provider ->
            SettingsNavigationRow(
                label = "Connect ${provider.displayName}",
                description = SignInSettingsViewModel.connectDescription(provider.displayName),
                onClick = { viewModel.onConnectNetwork(provider) },
                enabled = !state.busy,
            )
        }
        // Connect and disconnect finish after the browser or a dialog closes:
        // announce the outcome, which appears away from where focus is.
        val announced = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        state.message?.let { SettingsProse(body = it, modifier = announced) }
        state.error?.let { SettingsProse(body = it, title = "Couldn't finish", modifier = announced) }
    }

    state.passwordPrompt?.let { provider ->
        ConfirmPasswordDialog(
            providerName = provider.displayName,
            body = SignInSettingsViewModel.connectPrompt(provider.displayName),
            confirmLabel = "Continue",
            error = state.passwordError,
            busy = state.busy,
            onConfirm = viewModel::onConfirmPassword,
            onDismiss = viewModel::onDismissPasswordPrompt,
        )
    }
    state.networkPrompt?.let { provider ->
        ConfirmPasswordDialog(
            providerName = provider.displayName,
            body = SignInSettingsViewModel.connectNetworkPrompt(provider.displayName, provider.networkIdentity?.label),
            confirmLabel = "Connect",
            error = state.passwordError,
            busy = state.busy,
            onConfirm = viewModel::onConfirmNetwork,
            onDismiss = viewModel::onDismissNetworkPrompt,
        )
    }
    state.directoryPrompt?.let { provider ->
        ConnectDirectoryDialog(
            providerName = provider.displayName,
            error = state.passwordError,
            busy = state.busy,
            onConfirm = viewModel::onConfirmDirectory,
            onDismiss = viewModel::onDismissDirectoryPrompt,
        )
    }
    state.confirmDisconnect?.let { identity ->
        SiloConfirmDialog(
            title = "Disconnect ${SignInSettingsViewModel.providerLabel(identity)}?",
            body = SignInSettingsViewModel.disconnectConfirmBody(identity, state.canUnlink),
            confirmLabel = "Disconnect",
            onConfirm = viewModel::onConfirmDisconnect,
            onDismiss = viewModel::onDismissDisconnect,
        )
    }
}

private fun identityDescription(identity: AccountIdentity): String = buildString {
    val who = identity.accountLabel
    if (who.isNotBlank()) append("Signed in as ").append(who)
    formatDate(identity.linkedAt)?.let {
        if (isNotEmpty()) append(" · ")
        append("Connected ").append(it)
    }
}

private fun formatDate(instant: String): String? = runCatching {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(instant))
}.getOrNull()

@Composable
private fun ConfirmPasswordDialog(
    providerName: String,
    body: String,
    confirmLabel: String,
    error: String?,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(SettingsDimens.cardRadius),
        containerColor = SiloSurfaceContainer,
        titleContentColor = SiloForeground,
        textContentColor = SiloMutedText,
        title = { Text("Connect $providerName") },
        text = {
            Column {
                Text(body)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = SiloDestructive) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy && password.isNotEmpty()) onConfirm(password) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotEmpty(), onClick = { onConfirm(password) }) {
                Text(if (busy) "Checking…" else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConnectDirectoryDialog(
    providerName: String,
    error: String?,
    busy: Boolean,
    onConfirm: (password: String, username: String, directoryPassword: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var directoryPassword by remember { mutableStateOf("") }
    val complete = !busy && password.isNotEmpty() && username.isNotBlank() && directoryPassword.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(SettingsDimens.cardRadius),
        containerColor = SiloSurfaceContainer,
        titleContentColor = SiloForeground,
        textContentColor = SiloMutedText,
        title = { Text("Connect $providerName") },
        text = {
            Column {
                Text(SignInSettingsViewModel.connectDirectoryPrompt(providerName))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("This account's password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("$providerName username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = directoryPassword,
                    onValueChange = { directoryPassword = it },
                    label = { Text("$providerName password") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = SiloDestructive) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (complete) onConfirm(password, username, directoryPassword) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = complete,
                onClick = { onConfirm(password, username, directoryPassword) },
            ) {
                Text(if (busy) "Checking…" else "Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
