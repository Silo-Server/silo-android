package org.siloserver.silo.tv.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.discovery.DiscoveredServer
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.discovery.LanServerDiscovery
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.common.pairing.PairingAdvertisement
import org.siloserver.silo.common.pairing.PairingReceiver
import org.siloserver.silo.common.pairing.PairingReceiverStatus
import org.siloserver.silo.common.pairing.TvPairingAdvertiser
import org.siloserver.silo.common.pairing.TvDeviceName
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.common.ui.marquee.SignInHandoff
import org.siloserver.silo.tv.ui.components.TvHideStockImeOnDispose
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeBody
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButton
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButtonKind
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeErrorText
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeField
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeHeadline
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeScreen
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeSetupSteps
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeStatusChip
import org.siloserver.silo.tv.ui.components.marquee.TvUsePhoneCard
import org.siloserver.silo.tv.ui.components.rememberTvImeAwareFormScrollState
import org.siloserver.silo.tv.ui.components.tvImeAwareFieldContext
import org.siloserver.silo.common.network.ServerSetupScheme
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved

/**
 * First-run server setup on Android TV (silo-apple `TVServerSetupView`). The
 * screen advertises on the LAN the moment it appears, so the lead path is a
 * nearby phone or tablet setting this TV up with nothing to type, shown as
 * three numbered steps. Typing the address is one button away, under "No
 * phone nearby?". When a phone connects, the screen swaps in place to the
 * pairing receiver.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvServerSetupScreen(
    onContinueToLogin: (signupEnabled: Boolean) -> Unit,
    onNeedsSetup: () -> Unit,
    /**
     * A phone's session ended after signing in [accounts] (the saved accounts,
     * in the order they signed in). Opens the only one's picker, or Change
     * Account on the first when there are several.
     */
    onPairedAccounts: (accounts: List<org.siloserver.silo.common.pairing.PairingCommittedAccount>) -> Unit,
    /** A saved server this TV is still signed in to: straight to its profiles. */
    onSignedIn: () -> Unit,
    viewModel: TvServerSetupViewModel = koinViewModel(),
    pairingReceiver: PairingReceiver = koinInject(),
    pairingAdvertiser: TvPairingAdvertiser = koinInject(),
    discovery: LanServerDiscovery = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    // Silo servers on this network, listed on both pages while setup is on screen.
    LifecycleStartEffect(discovery) {
        discovery.start()
        onStopOrDispose { discovery.stop() }
    }
    val found by discovery.servers.collectAsState()
    val liveStatus by pairingReceiver.status.collectAsState()
    // Once the session is finished, keep its summary on screen: stopping the
    // advertiser returns the receiver to Idle, and opening the first account
    // can take a moment before navigation, which flashed the setup page.
    var finishedStatus by remember { mutableStateOf<PairingReceiverStatus?>(null) }
    val pairingStatus = finishedStatus ?: liveStatus
    val isActivePairing = pairingStatus.isActivePairing
    var enteringAddress by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { MarqueeScene.showGeneric() }

    // Companion LAN pairing: advertise `_silopair._tcp` while this screen is on
    // so a phone running Silo can push the server URL + drive device-login,
    // sparing the viewer from typing a URL on the remote. Advertising stops
    // when the screen leaves the composition.
    var advertiserToken by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        val token = pairingAdvertiser.start(PairingAdvertisement.Setup)
        advertiserToken = token
        onDispose { pairingAdvertiser.stop(token) }
    }
    // The dwell and the Continue button both finish; only the first counts.
    var pairingFinished by remember { mutableStateOf(false) }
    val finishPairing = {
        if (!pairingFinished) {
            pairingFinished = true
            // Read before stopping: stopping returns the receiver to Idle.
            val completed = pairingReceiver.status.value as? PairingReceiverStatus.Completed
            finishedStatus = completed
            val accounts = completed?.accounts.orEmpty()
            pairingAdvertiser.stop(advertiserToken)
            // A one-profile household with no PIN goes straight to Home.
            SignInHandoff.skipsSingleProfilePicker = true
            onPairedAccounts(accounts)
        }
    }
    LaunchedEffect(pairingStatus) {
        if (pairingStatus is PairingReceiverStatus.Completed) {
            delay(1_800)
            finishPairing()
        }
    }

    LaunchedEffect(state.navigateTo) {
        when (val dest = state.navigateTo) {
            is TvServerSetupDestination.Setup -> {
                viewModel.onNavigationConsumed()
                onNeedsSetup()
            }
            is TvServerSetupDestination.Login -> {
                viewModel.onNavigationConsumed()
                onContinueToLogin(dest.signupEnabled)
            }
            TvServerSetupDestination.Profiles -> {
                viewModel.onNavigationConsumed()
                onSignedIn()
            }
            null -> Unit
        }
    }

    state.pendingCleartextUrl?.let { origin ->
        AlertDialog(
            onDismissRequest = viewModel::cancelCleartextConnection,
            title = { androidx.compose.material3.Text(stringResource(CommonR.string.setup_cleartext_title)) },
            text = {
                androidx.compose.material3.Text(
                    stringResourceWithNames(CommonR.string.setup_cleartext_body, ServerBranding.hostLabel(origin)),
                )
            },
            confirmButton = {
                TvMarqueeButton(
                    text = stringResource(CommonR.string.setup_cleartext_connect),
                    onClick = viewModel::confirmCleartextConnection,
                    enabled = !state.isLoading,
                )
            },
            dismissButton = {
                TvMarqueeButton(
                    text = stringResource(CommonR.string.common_cancel),
                    onClick = viewModel::cancelCleartextConnection,
                    kind = TvMarqueeButtonKind.Plain,
                )
            },            containerColor = Color(0xFF1C1C1E),
            titleContentColor = MarqueeColors.Ink,
            textContentColor = MarqueeColors.InkSecondary,
        )
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        AnimatedContent(
            targetState = when {
                isActivePairing -> SetupPage.Pairing
                enteringAddress -> SetupPage.Address
                else -> SetupPage.Phone
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tvServerSetupPage",
        ) { page ->
            when (page) {
                SetupPage.Pairing -> ActivePairingPanel(
                    status = pairingStatus,
                    onCancel = pairingReceiver::cancelActiveSession,
                    onContinue = finishPairing,
                    onAllow = pairingReceiver::allowPendingServer,
                    onDeny = pairingReceiver::denyPendingServer,
                    onUseAlternate = pairingReceiver::useAlternateAddress,
                    onRetryAddress = pairingReceiver::retryPushedAddress,
                )
                SetupPage.Phone -> PhoneFirst(
                    state = state,
                    found = found,
                    onPickFound = viewModel::connectTo,
                    // A found server may be connecting; leaving now would let
                    // its result land behind manual entry.
                    onEnterAddress = { if (!state.isLoading) enteringAddress = true },
                )
                SetupPage.Address -> AddressEntry(
                    state = state,
                    found = found,
                    onPickFound = viewModel::connectTo,
                    onServerUrlChanged = viewModel::onServerUrlChanged,
                    onSchemeSelected = viewModel::onSchemeSelected,
                    onPortChanged = viewModel::onPortChanged,
                    onToggleAdvancedOptions = viewModel::onToggleAdvancedOptions,
                    onConnect = viewModel::onConnectClick,
                    // Leaving mid-probe would let a late connect pull the app on.
                    onBack = { if (!state.isLoading) enteringAddress = false },
                )
            }
        }
    }
}

private enum class SetupPage { Phone, Address, Pairing }

/** This TV's name, as the person named it in Android TV settings. */
@Composable
private fun rememberTvName(): String {
    val context = LocalContext.current
    return remember(context) { TvDeviceName.current(context) }
}

/**
 * Lands focus on [requester] for D-pad users once the page is up, and keeps
 * asking for a moment: a request made while the previous page is still
 * fading out can be dropped.
 */
@Composable
internal fun ClaimInitialFocus(requester: FocusRequester, isFocused: () -> Boolean, tag: String) {
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(inputMode) {
        // Pointer users click what they want, and in touch mode the claim
        // could not land anyway.
        if (inputMode == InputMode.Touch) return@LaunchedEffect
        val result = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = requester::requestFocus,
            isFocused = isFocused,
        )
        TvFocusLog.d { "$tag: claim result=$result" }
    }
}

@Composable
private fun PhoneFirst(
    state: TvServerSetupUiState,
    found: List<DiscoveredServer>,
    onPickFound: (DiscoveredServer) -> Unit,
    onEnterAddress: () -> Unit,
) {
    val enterAddressFocus = remember { FocusRequester() }
    var enterAddressFocused by remember { mutableStateOf(false) }
    ClaimInitialFocus(enterAddressFocus, { enterAddressFocused }, "serverSetup.phone")
    TvMarqueeScreen(
        accessory = { TvMarqueeStatusChip(rememberTvName(), icon = Icons.Outlined.Tv) },
        copy = {
            TvMarqueeStatusChip(
                stringResource(CommonR.string.tvSetup_phoneLooking),
                showsSpinner = true,
                modifier = Modifier.padding(bottom = 18.dp),
            )
            TvMarqueeHeadline(stringResource(CommonR.string.setup_phone_headline))
            TvMarqueeBody(
                stringResource(CommonR.string.setup_phone_body),
                size = 16.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                stringResource(CommonR.string.setup_phone_aside),
                color = MarqueeColors.InkTertiary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 32.dp),
            )
            // Servers found on this network connect with one press, above the
            // way to type an address.
            FoundServerRows(found, state, onPickFound, Modifier.padding(top = 9.dp))
            state.discoveryError?.let { TvMarqueeErrorText(it.text(), Modifier.width(380.dp).padding(top = 8.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                TvMarqueeButton(
                    text = stringResource(CommonR.string.setup_phone_enterAddress),
                    onClick = onEnterAddress,
                    kind = TvMarqueeButtonKind.Glass,
                    icon = Icons.Outlined.Language,
                    focusRequester = enterAddressFocus,
                    modifier = Modifier.onFocusChanged { enterAddressFocused = it.isFocused },
                )
            }
        },
        card = { TvMarqueeSetupSteps() },
    )
}

@Composable
private fun AddressEntry(
    state: TvServerSetupUiState,
    found: List<DiscoveredServer>,
    onPickFound: (DiscoveredServer) -> Unit,
    onServerUrlChanged: (String) -> Unit,
    onSchemeSelected: (ServerSetupScheme) -> Unit,
    onPortChanged: (String) -> Unit,
    onToggleAdvancedOptions: () -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    TvHideStockImeOnDispose()
    BackHandler(onBack = onBack)
    val fieldFocus = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    ClaimInitialFocus(fieldFocus, { fieldFocused }, "serverSetup.address")
    // A blank address still answers, with "Please enter a server host."
    val connect = { if (!state.isLoading) onConnect() }
    TvMarqueeScreen(
        copyScroll = rememberTvImeAwareFormScrollState(),
        accessory = { TvMarqueeStatusChip(rememberTvName(), icon = Icons.Outlined.Tv) },
        copy = {
            TvMarqueeHeadline(stringResource(CommonR.string.setup_headline))
            TvMarqueeBody(stringResource(CommonR.string.setup_body), modifier = Modifier.padding(top = 11.dp))
            Box(Modifier.width(380.dp).padding(top = 20.dp).tvImeAwareFieldContext()) {
                TvMarqueeField(
                    value = state.serverUrl,
                    onValueChange = onServerUrlChanged,
                    icon = Icons.Outlined.Language,
                    placeholder = stringResource(CommonR.string.setup_placeholder),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    onImeAction = connect,
                    isError = state.error != null,
                    // Fixed while the address is probed, so what's shown is
                    // what's connecting.
                    enabled = !state.isLoading,
                    focusRequester = fieldFocus,
                    onFocusChange = { fieldFocused = it },
                )
            }
            state.error?.let { TvMarqueeErrorText(it.text(), Modifier.width(380.dp).padding(top = 8.dp)) }
            TvMarqueeButton(
                text = stringResource(if (state.showsAdvancedOptions) CommonR.string.setup_advanced_hide else CommonR.string.setup_advanced_show),
                onClick = { if (!state.isLoading) onToggleAdvancedOptions() },
                kind = TvMarqueeButtonKind.Plain,
                compact = true,
                icon = Icons.Outlined.Settings,
                modifier = Modifier.padding(top = 9.dp),
            )
            if (state.showsAdvancedOptions) {
                AdvancedOptions(state, onSchemeSelected, onPortChanged, onSubmit = connect, Modifier.padding(top = 7.dp))
            }
            Text(
                stringResource(
                    if (state.usesCleartext) CommonR.string.setup_cleartext_warning else CommonR.string.tvSignIn_server_httpsNote,
                ),
                color = if (state.usesCleartext) MarqueeColors.Warning else MarqueeColors.InkTertiary,
                fontSize = 14.sp,
                modifier = Modifier.width(380.dp).padding(top = 9.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                TvMarqueeButton(
                    text = stringResource(if (state.isLoading) CommonR.string.setup_connecting else CommonR.string.common_connect),
                    onClick = connect,
                    isLoading = state.isLoading,
                )
                TvMarqueeButton(
                    text = stringResource(CommonR.string.common_back),
                    onClick = onBack,
                    kind = TvMarqueeButtonKind.Plain,
                )
            }
            if (found.isNotEmpty()) {
                Text(
                    stringResource(CommonR.string.setup_foundNearby),
                    color = MarqueeColors.InkTertiary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 24.dp),
                )
                FoundServerRows(found, state, onPickFound, Modifier.padding(top = 9.dp))
            }
            state.discoveryError?.let { TvMarqueeErrorText(it.text(), Modifier.width(380.dp).padding(top = 8.dp)) }
        },
        card = {
            TvUsePhoneCard(stringResource(CommonR.string.tvSignIn_server_phoneCard))
        },
    )
}

/**
 * Up to two one-press rows for servers found on this network, each showing
 * its name and address. Rows stay enabled while connecting so the focused
 * one keeps focus; the view model ignores repeat presses. Renders nothing
 * until a server is confirmed.
 */
@Composable
private fun FoundServerRows(
    found: List<DiscoveredServer>,
    state: TvServerSetupUiState,
    onPick: (DiscoveredServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (found.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        found.take(2).forEach { server ->
            // Keyed by server, so focus stays on the same server when the list re-sorts.
            key(server.serverId) {
                val rowDescription = stringResourceWithNames(CommonR.string.connect_discovery_serverRow, server.name, server.detail)
                TvMarqueeButton(
                    onClick = { onPick(server) },
                    kind = TvMarqueeButtonKind.Glass,
                    isLoading = state.isLoading && state.connectingServerUrl == server.url,
                    modifier = Modifier.semantics { contentDescription = rowDescription },
                ) { ink ->
                    Text(server.name, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        server.detail,
                        color = ink.copy(alpha = 0.62f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The protocol to try (Auto, HTTPS, HTTP) and an optional port, as Apple TV's
 * Advanced options. Auto tries HTTPS, then HTTP, then Silo's port 8090.
 */
@Composable
private fun AdvancedOptions(
    state: TvServerSetupUiState,
    onSchemeSelected: (ServerSetupScheme) -> Unit,
    onPortChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerSetupScheme.entries.forEach { scheme ->
            val selected = state.selectedScheme == scheme
            TvMarqueeButton(
                text = scheme.displayLabel(),
                onClick = { if (!state.isLoading) onSchemeSelected(scheme) },
                kind = TvMarqueeButtonKind.Glass,
                compact = true,
                icon = if (selected) Icons.Filled.Check else null,
                modifier = Modifier.semantics { this.selected = selected },
            )
        }
        Box(Modifier.width(130.dp).tvImeAwareFieldContext()) {
            TvMarqueeField(
                value = state.port,
                onValueChange = onPortChanged,
                icon = Icons.Outlined.Tag,
                placeholder = stringResource(CommonR.string.setup_port_placeholder),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Go,
                onImeAction = onSubmit,
                enabled = !state.isLoading,
            )
        }
    }
}

/** The protocol choice's label; [ServerSetupScheme.label] stays the English name. */
@Composable
private fun ServerSetupScheme.displayLabel(): String = stringResource(
    when (this) {
        ServerSetupScheme.Auto -> CommonR.string.setup_scheme_auto
        ServerSetupScheme.Https -> CommonR.string.setup_scheme_https
        ServerSetupScheme.Http -> CommonR.string.setup_scheme_http
    },
)
