package org.siloserver.silo.android.ui.screens.settings

import org.siloserver.silo.android.ui.components.SiloDialogActionStyle
import org.siloserver.silo.android.ui.components.SiloDialogAction
import org.siloserver.silo.android.ui.components.SiloDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
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

    SettingsSection(title = stringResource(CommonR.string.settings_signIn_title), modifier = modifier) {
        state.identities.forEach { identity ->
            SettingsRow(
                label = identity.providerName.ifBlank { stringResource(CommonR.string.settings_signIn_providerTurnedOff) },
                description = identityDescription(identity),
            )
            // The server says up front when this is the only way to sign in.
            if (state.canUnlink != false) {
                SettingsDestructiveRow(
                    label = stringResource(CommonR.string.settings_signIn_disconnect),
                    description = settingsText(
                        CommonR.string.settings_signIn_disconnectDescription,
                        SignInSettingsViewModel.providerLabel(identity),
                    ).resolve(),
                    onClick = { viewModel.onDisconnect(identity) },
                    enabled = !state.busy,
                )
            }
        }
        if (state.canUnlink == false) {
            SettingsProse(body = SignInSettingsViewModel.ONLY_SIGN_IN_METHOD.resolve())
        }
        state.connectable.forEach { provider ->
            SettingsNavigationRow(
                label = stringResourceWithNames(CommonR.string.settings_signIn_connect, provider.displayName),
                description = SignInSettingsViewModel.connectDescription(provider.displayName).resolve(),
                onClick = { viewModel.onConnect(provider) },
                enabled = !state.busy,
            )
        }
        state.directory?.let { provider ->
            SettingsNavigationRow(
                label = stringResourceWithNames(CommonR.string.settings_signIn_connect, provider.displayName),
                description = SignInSettingsViewModel.connectDirectoryDescription(provider.displayName).resolve(),
                onClick = { viewModel.onConnectDirectory(provider) },
                enabled = !state.busy,
            )
        }
        state.network?.let { provider ->
            SettingsNavigationRow(
                label = stringResourceWithNames(CommonR.string.settings_signIn_connect, provider.displayName),
                description = SignInSettingsViewModel.connectNetworkDescription(provider.displayName, state.networkLinkKeepsPassword).resolve(),
                onClick = { viewModel.onConnectNetwork(provider) },
                enabled = !state.busy,
            )
        }
        // Connect and disconnect finish after the browser or a dialog closes:
        // announce the outcome, which appears away from where focus is.
        val announced = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        state.message?.let { SettingsProse(body = it.resolve(), modifier = announced) }
        state.error?.let {
            SettingsProse(body = it.resolve(), title = stringResource(CommonR.string.settings_signIn_couldNotFinish), modifier = announced)
        }
    }

    state.passwordPrompt?.let { provider ->
        ConfirmPasswordDialog(
            providerName = provider.displayName,
            body = SignInSettingsViewModel.connectPrompt(provider.displayName).resolve(),
            confirmLabel = stringResource(CommonR.string.settings_signIn_continue),
            error = state.passwordError?.resolve(),
            busy = state.busy,
            onConfirm = viewModel::onConfirmPassword,
            onDismiss = viewModel::onDismissPasswordPrompt,
        )
    }
    state.networkPrompt?.let { provider ->
        ConfirmPasswordDialog(
            providerName = provider.displayName,
            body = SignInSettingsViewModel.connectNetworkPrompt(
                provider.displayName,
                provider.networkIdentity?.label,
                state.networkLinkKeepsPassword,
            ).resolve(),
            confirmLabel = stringResource(CommonR.string.common_connect),
            error = state.passwordError?.resolve(),
            busy = state.busy,
            onConfirm = viewModel::onConfirmNetwork,
            onDismiss = viewModel::onDismissNetworkPrompt,
        )
    }
    state.directoryPrompt?.let { provider ->
        ConnectDirectoryDialog(
            providerName = provider.displayName,
            error = state.passwordError?.resolve(),
            busy = state.busy,
            onConfirm = viewModel::onConfirmDirectory,
            onDismiss = viewModel::onDismissDirectoryPrompt,
        )
    }
    state.confirmDisconnect?.let { identity ->
        SiloConfirmDialog(
            title = settingsText(
                CommonR.string.settings_signIn_disconnectConfirm_title,
                SignInSettingsViewModel.providerLabel(identity),
            ).resolve(),
            body = SignInSettingsViewModel.disconnectConfirmBody(identity, state.canUnlink).resolve(),
            confirmLabel = stringResource(CommonR.string.settings_signIn_disconnect),
            onConfirm = viewModel::onConfirmDisconnect,
            onDismiss = viewModel::onDismissDisconnect,
        )
    }
}

@Composable
private fun identityDescription(identity: AccountIdentity): String {
    val who = identity.accountLabel.takeIf { it.isNotBlank() }
    val date = formatDate(identity.linkedAt)
    return when {
        who != null && date != null -> stringResourceWithNames(CommonR.string.settings_signIn_identity_signedInAsConnected, who, date)
        who != null -> stringResourceWithNames(CommonR.string.settings_signIn_identity_signedInAs, who)
        date != null -> stringResource(CommonR.string.settings_signIn_identity_connected, date)
        else -> ""
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
    SiloDialog(
        title = stringResourceWithNames(CommonR.string.settings_signIn_connect, providerName),
        message = body,
        onDismissRequest = onDismiss,
        actions = listOf(
            SiloDialogAction(
                label = if (busy) stringResource(CommonR.string.settings_signIn_checking) else confirmLabel,
                onClick = { onConfirm(password) },
                style = SiloDialogActionStyle.Primary,
                enabled = !busy && password.isNotEmpty(),
            ),
            SiloDialogAction(stringResource(CommonR.string.common_cancel), onDismiss),
        ),
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(CommonR.string.settings_signIn_password)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it, color = SiloDestructive) } },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!busy && password.isNotEmpty()) onConfirm(password) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
    SiloDialog(
        title = stringResourceWithNames(CommonR.string.settings_signIn_connect, providerName),
        message = SignInSettingsViewModel.connectDirectoryPrompt(providerName).resolve(),
        onDismissRequest = onDismiss,
        actions = listOf(
            SiloDialogAction(
                label = stringResource(if (busy) CommonR.string.settings_signIn_checking else CommonR.string.common_connect),
                onClick = { onConfirm(password, username, directoryPassword) },
                style = SiloDialogActionStyle.Primary,
                enabled = complete,
            ),
            SiloDialogAction(stringResource(CommonR.string.common_cancel), onDismiss),
        ),
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(CommonR.string.settings_signIn_accountPassword)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResourceWithNames(CommonR.string.settings_signIn_directoryUsername, providerName)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = directoryPassword,
            onValueChange = { directoryPassword = it },
            label = { Text(stringResourceWithNames(CommonR.string.settings_signIn_directoryPassword, providerName)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it, color = SiloDestructive) } },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (complete) onConfirm(password, username, directoryPassword) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
