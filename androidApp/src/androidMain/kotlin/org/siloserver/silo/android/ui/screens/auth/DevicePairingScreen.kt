package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.material.icons.rounded.Dns
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.android.ui.components.SiloMenuItem
import org.siloserver.silo.android.ui.components.SiloDropdownMenu
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.produceState
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.currentAccountOn
import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.android.ui.components.SiloDialog
import org.siloserver.silo.android.ui.components.SiloDialogAction
import org.siloserver.silo.repository.ExternalSignInRepository
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.SignInOptions
import org.siloserver.silo.util.parseRfc3339ToEpochMillis
import org.siloserver.silo.viewmodel.DeviceFollowOutcome
import org.siloserver.silo.viewmodel.DevicePairingError
import org.siloserver.silo.viewmodel.DevicePairingUiState
import org.siloserver.silo.viewmodel.DevicePairingViewModel

/**
 * "Sign in a TV": approve a TV's device sign-in from this phone, by typing
 * the code the TV shows or from the web page's `silo://device` link. Codes are
 * unique only per server, so the code is looked up on one chosen server (the
 * active one preselected), never on every saved server.
 */
@Composable
fun DevicePairingScreen(
    token: String?,
    code: String?,
    onDone: () -> Unit,
    /**
     * "Sign in" after the chosen server answered 401: sign in on [serverId]'s
     * server (the one the code was looked up on), keeping [code] for afterwards.
     */
    onSignIn: (serverId: String?, code: String?) -> Unit,
    serverId: String? = null,
    /**
     * The link named [serverId]: approve on that saved server with its own
     * credentials, without switching the active server, and offer no other.
     */
    lockServer: Boolean = false,
    /**
     * "Not you?": approve with another saved account on [serverId]'s server
     * (`accountId`), or add one (`accountId` null), keeping [code] for
     * afterwards; the phone's current account stays signed in. `startSignIn`
     * is true when adding one asks the provider to offer another account, so
     * the login screen starts that sign-in by itself.
     */
    onSwitchAccount: (serverId: String?, code: String?, accountId: String?, startSignIn: Boolean) -> Unit,
    viewModel: DevicePairingViewModel = koinViewModel(
        parameters = { parametersOf(token to code, serverId, lockServer) },
    ),
) {
    val state by viewModel.uiState.collectAsState()
    // "Not you?" signs out of Silo only. Its wording follows what the next
    // sign-in offers (silo-apple `TVApprovalAccountSwitch`).
    val externalSignIn: ExternalSignInRepository = koinInject()
    val serverRegistry: ServerRegistry = koinInject()
    val activeEntry by serverRegistry.activeEntry.collectAsState()
    val switchServerUrl = state.selectedServer?.url ?: activeEntry?.url
    // The other saved accounts on the server the code is looked up on.
    val accounts by serverRegistry.accounts.collectAsState()
    val activeAccountId by serverRegistry.activeAccountId.collectAsState()
    val switchServerId = state.selectedServer?.id ?: activeEntry?.id
    val approvingAccountId = if (switchServerId == activeEntry?.id) {
        activeAccountId
    } else {
        switchServerId?.let { serverRegistry.currentAccountOn(it)?.localAccountId }
    }
    val otherAccounts = accounts
        .filter { it.serverId == switchServerId && it.localAccountId != approvingAccountId }
        .sortedBy { it.addedAtEpochMs }
    val accountSwitch by produceState(initialValue = ApprovalAccountSwitch.SignOut, switchServerUrl) {
        value = ApprovalAccountSwitch.SignOut
        value = ApprovalAccountSwitch.of(switchServerUrl?.let { externalSignIn.signInOptions(it) })
    }

    AuthStage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SiloLogo()
            Spacer(modifier = Modifier.height(18.dp))

            val lookup = state.lookup
            when {
                state.completedStatus != null -> DecisionResult(state = state, onDone = onDone)
                lookup != null -> ApprovalCard(
                    lookup = lookup,
                    state = state,
                    onApprove = viewModel::approve,
                    onDeny = viewModel::deny,
                    onRetryAccount = viewModel::retryAccount,
                    otherAccounts = otherAccounts,
                    onSwitchAccount = { accountId ->
                        val (server, typed) = state.signInRequest()
                        onSwitchAccount(
                            server,
                            typed,
                            accountId,
                            accountId == null && accountSwitch == ApprovalAccountSwitch.ChooseAccount,
                        )
                    },
                )
                else -> CodeEntry(
                    state = state,
                    onCodeChanged = viewModel::onCodeChanged,
                    onLookup = viewModel::lookup,
                    onSelectServer = viewModel::selectServer,
                )
            }

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(18.dp))
                AuthErrorBanner(
                    message = error.message(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (error == DevicePairingError.SignInFirst) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val (server, typed) = state.signInRequest()
                            onSignIn(server, typed)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(CommonR.string.signInTv_signInFirst))
                    }
                }
                if (state.notFound) {
                    Spacer(modifier = Modifier.height(8.dp))
                    state.otherServers.forEach { server ->
                        TextButton(
                            onClick = { viewModel.selectServer(server.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(CommonR.string.signInTv_tryServer, server.displayName))
                        }
                    }
                }
            }
        }
    }
}

/**
 * What "Sign in" and "Switch account" carry to the sign-in flow: the server
 * the code was looked up on (the chooser's pick, not necessarily the active
 * server) and the typed code, so the person comes back to it afterwards.
 */
internal fun DevicePairingUiState.signInRequest(): Pair<String?, String?> =
    selectedServerId to code.takeIf { it.isNotBlank() }

@Composable
private fun CodeEntry(
    state: DevicePairingUiState,
    onCodeChanged: (String) -> Unit,
    onLookup: () -> Unit,
    onSelectServer: (String) -> Unit,
) {
    Text(
        text = stringResource(CommonR.string.signInTv_title),
        style = MaterialTheme.typography.headlineMedium,
        color = AuthColors.OnBackground,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(CommonR.string.signInTv_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = AuthColors.OnSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))

    if (state.canChooseServer) {
        ServerChooser(state = state, onSelectServer = onSelectServer)
        Spacer(modifier = Modifier.height(16.dp))
    }

    // A token link carries no code, so Continue retries its lookup.
    val canSubmit = (!state.token.isNullOrBlank() || state.code.length == DeviceCodeFormat.LENGTH) && !state.isLoading
    OutlinedTextField(
        value = state.code,
        onValueChange = onCodeChanged,
        enabled = state.token.isNullOrBlank(),
        label = { Text(stringResource(CommonR.string.signInTv_codeLabel)) },
        placeholder = { Text("4821 7730") },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
        ),
        visualTransformation = GroupedCodeTransformation,
        // A text keyboard, not a keypad: current servers issue digits, but
        // older ones issue letters and digits (DeviceCodeFormat).
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { if (canSubmit) onLookup() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = onLookup,
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = AuthColors.Primary),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(CommonR.string.signInTv_continue))
        }
    }
}

@Composable
private fun ServerChooser(
    state: DevicePairingUiState,
    onSelectServer: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.selectedServer
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(CommonR.string.signInTv_serverLabel),
            style = MaterialTheme.typography.labelMedium,
            color = AuthColors.OnSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = selected?.displayName.orEmpty(), color = AuthColors.OnBackground)
                    // Saved servers often share a name ("Silo"); the address
                    // is what tells them apart.
                    selected?.let { server ->
                        Text(
                            text = ServerBranding.hostLabel(server.url),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuthColors.OnSurfaceVariant,
                        )
                    }
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AuthColors.OnBackground)
            }
            SiloDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.servers.forEach { server ->
                    SiloMenuItem(
                        label = server.displayName,
                        detail = ServerBranding.hostLabel(server.url),
                        icon = Icons.Rounded.Dns,
                        checked = server.id == selected?.id,
                        onClick = {
                            expanded = false
                            onSelectServer(server.id)
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(CommonR.string.signInTv_serverHint),
            style = MaterialTheme.typography.bodySmall,
            color = AuthColors.OnSurfaceVariant,
        )
    }
}

/**
 * The approval card: which TV, the code to check, which server and account,
 * what approving grants, and the device-code phishing warning (RFC 8628 §5.4).
 */
@Composable
private fun ApprovalCard(
    lookup: DeviceLoginLookupResponse,
    state: DevicePairingUiState,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onRetryAccount: () -> Unit,
    otherAccounts: List<AccountEntry>,
    onSwitchAccount: (accountId: String?) -> Unit,
) {
    val deviceName = lookup.deviceName?.takeIf { it.isNotBlank() }
    var confirmsSwitchAccount by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AuthColors.Surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = deviceName?.let { stringResource(CommonR.string.signInTv_cardTitle, it) }
                    ?: stringResource(CommonR.string.signInTv_cardTitleGeneric),
                style = MaterialTheme.typography.titleLarge,
                color = AuthColors.OnBackground,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            // "Android TV · requested just now", as on the web approval page.
            val platform = platformLabel(lookup.devicePlatform)
            val requested = requestedLabel(lookup.requestedAt)
            val deviceLine = when {
                platform != null && requested != null ->
                    stringResource(CommonR.string.signInTv_platformRequested, platform, requested)
                platform != null -> platform
                requested != null -> stringResource(CommonR.string.signInTv_requested, requested)
                else -> null
            }
            deviceLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuthColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(CommonR.string.signInTv_checkCode),
                style = MaterialTheme.typography.bodyMedium,
                color = AuthColors.OnSurfaceVariant,
            )
            val code = lookup.userCode?.takeIf { it.isNotBlank() } ?: state.code
            val spoken = DeviceCodeFormat.spoken(code)
            Text(
                text = DeviceCodeFormat.display(code),
                style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
                color = AuthColors.OnBackground,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = spoken
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Name and host, so a same-code collision or a lookup on the wrong
            // server is visible before anyone approves.
            val serverName = lookup.serverName?.takeIf { it.isNotBlank() }
                ?: state.selectedServer?.displayName
            val serverHost = state.selectedServer?.url?.let(DeviceCodeFormat::host)
            val serverLabel = tvSignInServerLabel(serverName, serverHost)
            val serverLine = if (state.accountUnconfirmed) {
                null
            } else {
                tvSignInLine(serverName, serverHost, state.accountName)
            }
            serverLine?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = AuthColors.OnBackground, textAlign = TextAlign.Center)
            }
            // A saved server other than the active one whose account couldn't
            // be read: approving would sign the TV in to an account nobody saw.
            // A provider outage says so rather than blaming the account.
            if (state.accountUnconfirmed) {
                Text(
                    text = state.accountError?.message() ?: stringResource(
                        CommonR.string.signInTv_accountUnconfirmed,
                        serverLabel ?: state.selectedServer?.displayName.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFC107),
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetryAccount) {
                    Text(stringResource(CommonR.string.common_tryAgain))
                }
            }
            TextButton(onClick = { confirmsSwitchAccount = true }, enabled = !state.isSubmitting) {
                Text(stringResource(CommonR.string.signInTv_switchAccount))
            }
            if (confirmsSwitchAccount) {
                // Another saved account on this server, or a new one; the
                // phone's current account stays signed in (spec §4.6).
                val switchServerName = serverName ?: state.selectedServer?.displayName
                    ?: stringResource(CommonR.string.signInTv_switchThisServer)
                val signInAgain = stringResource(CommonR.string.picker_signInAgain)
                val unnamedAccount = stringResource(CommonR.string.account_unnamed)
                SiloDialog(
                    title = stringResource(CommonR.string.signInTv_chooseAccountTitle),
                    message = stringResource(CommonR.string.signInTv_chooseAccountBody, switchServerName),
                    onDismissRequest = { confirmsSwitchAccount = false },
                    actions = buildList {
                        otherAccounts.forEach { account ->
                            val name = account.username?.takeIf { it.isNotBlank() } ?: unnamedAccount
                            add(
                                SiloDialogAction(
                                    label = if (account.needsSignIn) "$name · $signInAgain" else name,
                                    onClick = {
                                        confirmsSwitchAccount = false
                                        onSwitchAccount(account.localAccountId)
                                    },
                                ),
                            )
                        }
                        add(
                            SiloDialogAction(
                                label = stringResource(CommonR.string.signInTv_addAccount, switchServerName),
                                onClick = {
                                    confirmsSwitchAccount = false
                                    onSwitchAccount(null)
                                },
                            ),
                        )
                        add(SiloDialogAction(stringResource(CommonR.string.common_cancel), { confirmsSwitchAccount = false }))
                    },
                )
            }
            Text(
                text = stringResource(CommonR.string.signInTv_profilesNote),
                style = MaterialTheme.typography.bodySmall,
                color = AuthColors.OnSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(CommonR.string.signInTv_warning),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFC107),
                textAlign = TextAlign.Center,
            )
            lookup.ipAddressHint?.takeIf { it.isNotBlank() }?.let { network ->
                Text(
                    text = stringResource(CommonR.string.signInTv_network, network),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuthColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onApprove,
                enabled = state.canApprove,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AuthColors.Primary),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(CommonR.string.signInTv_approve))
                }
            }
            TextButton(onClick = onDeny, enabled = state.canDecide, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(CommonR.string.signInTv_notNow), color = AuthColors.OnSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DecisionResult(state: DevicePairingUiState, onDone: () -> Unit) {
    val approved = state.completedStatus == "approved"
    val outcome = state.followOutcome
    val succeeded = approved && (outcome == null || outcome == DeviceFollowOutcome.SignedIn)
    Icon(
        imageVector = if (succeeded) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
        contentDescription = null,
        tint = if (succeeded) Color(0xFF34C759) else AuthColors.OnSurfaceVariant,
        modifier = Modifier.size(48.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(
            when {
                !approved -> CommonR.string.signInTv_declined
                outcome == null -> CommonR.string.signInTv_signingIn
                else -> when (outcome) {
                    DeviceFollowOutcome.SignedIn -> CommonR.string.signInTv_signedIn
                    DeviceFollowOutcome.Canceled -> CommonR.string.signInTv_followCanceled
                    DeviceFollowOutcome.Expired -> CommonR.string.signInTv_followExpired
                    DeviceFollowOutcome.Denied -> CommonR.string.signInTv_followDenied
                    DeviceFollowOutcome.TimedOut -> CommonR.string.signInTv_followTimedOut
                }
            },
        ),
        style = MaterialTheme.typography.titleMedium,
        color = AuthColors.OnBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = AuthColors.Primary),
    ) {
        Text(stringResource(CommonR.string.common_done))
    }
}

@Composable
private fun DevicePairingError.message(): String = when (this) {
    DevicePairingError.EnterCode -> stringResource(CommonR.string.signInTv_error_enterCode)
    DevicePairingError.SignInFirst -> stringResource(CommonR.string.signInTv_error_signInFirst)
    DevicePairingError.NotFound -> stringResource(CommonR.string.signInTv_error_notFound)
    is DevicePairingError.NotFoundOn -> stringResource(CommonR.string.signInTv_error_notFoundOn, serverName)
    DevicePairingError.Expired -> stringResource(CommonR.string.signInTv_error_expired)
    DevicePairingError.AlreadySignedIn -> stringResource(CommonR.string.signInTv_error_alreadySignedIn)
    DevicePairingError.Declined -> stringResource(CommonR.string.signInTv_error_declined)
    DevicePairingError.Canceled -> stringResource(CommonR.string.signInTv_error_canceled)
    DevicePairingError.Network -> stringResource(CommonR.string.signInTv_error_network)
    DevicePairingError.ProviderUnavailable -> stringResource(CommonR.string.signInTv_error_providerUnavailable)
    is DevicePairingError.Server -> message ?: stringResource(CommonR.string.signInTv_error_generic)
}

/** "just now" / "5 minutes ago" for a lookup's `requested_at`; null when absent or unreadable. */
@Composable
private fun requestedLabel(requestedAt: String?): String? {
    val at = requestedAt?.let(::parseRfc3339ToEpochMillis) ?: return null
    val now = System.currentTimeMillis()
    // Clocks differ a little between phone and server: a time "in the future"
    // is just now too.
    return if (now - at < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(CommonR.string.signInTv_requestedJustNow)
    } else {
        DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}

/** "Android TV" / "Apple TV" for the platform strings TVs send; anything else as sent. */
@Composable
private fun platformLabel(platform: String?): String? {
    val raw = platform?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (raw.lowercase().replace("_", "-")) {
        "android-tv", "androidtv" -> stringResource(CommonR.string.platform_androidTv)
        "tvos", "apple-tv", "appletv" -> stringResource(CommonR.string.platform_appleTv)
        else -> raw
    }
}

/** Shows eight typed characters grouped 4+4 (`4821 7730`) without storing the space. */
private object GroupedCodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length <= 4) return TransformedText(text, OffsetMapping.Identity)
        val grouped = raw.substring(0, 4) + " " + raw.substring(4)
        return TransformedText(
            AnnotatedString(grouped),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = if (offset <= 4) offset else offset + 1
                override fun transformedToOriginal(offset: Int) = if (offset <= 4) offset else offset - 1
            },
        )
    }
}

/**
 * "You'll sign it in to <server> (<host>) as <account>.", or "…with your
 * account" when the account couldn't be read. The host is left out when it is
 * blank or the same as the name. Null when neither is known. Shared by the
 * code-entry approval and the nearby-TV card.
 */
@Composable
internal fun tvSignInLine(serverName: String?, serverHost: String?, accountName: String?): String? {
    val server = tvSignInServerLabel(serverName, serverHost) ?: return null
    return if (accountName != null) {
        stringResource(CommonR.string.signInTv_serverAccount, server, accountName)
    } else {
        stringResource(CommonR.string.signInTv_serverOnly, server)
    }
}

/** "<server> (<host>)", the name alone when the host is blank or the same, or null when neither is known. */
@Composable
internal fun tvSignInServerLabel(serverName: String?, serverHost: String?): String? {
    val name = serverName?.takeIf { it.isNotBlank() }
    val host = serverHost?.takeIf { it.isNotBlank() }
    return when {
        name == null -> host
        host == null || host.equals(name, ignoreCase = true) -> name
        else -> stringResource(CommonR.string.signInTv_serverWithHost, name, host)
    }
}

/**
 * What "Not you?" on the approval card offers, from the server's sign-in
 * options (silo-apple `TVApprovalAccountSwitch`).
 */
internal enum class ApprovalAccountSwitch {
    /** A browser provider that takes `select_account`: it's asked to offer another account. */
    ChooseAccount,

    /** No browser provider: the password form that follows lets the person choose the account. */
    SwitchAccount,

    /**
     * A browser provider without `select_account`, or options that couldn't
     * be read: the provider may sign the same person straight back in, so
     * only a sign-out is promised.
     */
    SignOut,
    ;

    companion object {
        fun of(options: SignInOptions?): ApprovalAccountSwitch = when {
            options == null -> SignOut
            options.oauthProviders.isEmpty() -> SwitchAccount
            options.selectAccount -> ChooseAccount
            else -> SignOut
        }
    }
}
