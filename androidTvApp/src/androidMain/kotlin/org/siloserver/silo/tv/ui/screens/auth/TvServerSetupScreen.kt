package org.siloserver.silo.tv.ui.screens.auth

import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.pairing.PairingAdvertisement
import org.siloserver.silo.common.pairing.PairingReceiver
import org.siloserver.silo.common.pairing.PairingReceiverStatus
import org.siloserver.silo.common.pairing.TvPairingAdvertiser
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.common.ui.marquee.SignInHandoff
import org.siloserver.silo.tv.R
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
    onPairedSignIn: () -> Unit = {},
    /** A saved server this TV is still signed in to: straight to its profiles. */
    onSignedIn: () -> Unit = onPairedSignIn,
    viewModel: TvServerSetupViewModel = koinViewModel(),
    pairingReceiver: PairingReceiver = koinInject(),
    pairingAdvertiser: TvPairingAdvertiser = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val pairingStatus by pairingReceiver.status.collectAsState()
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
    val finishPairing = {
        pairingAdvertiser.stop(advertiserToken)
        // A one-profile household with no PIN goes straight to Home.
        SignInHandoff.skipsSingleProfilePicker = true
        onPairedSignIn()
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
            title = { androidx.compose.material3.Text("Connect without encryption?") },
            text = {
                androidx.compose.material3.Text(
                    "Your password and what you watch will be sent unencrypted to " +
                        "${ServerBranding.hostLabel(origin)}. Only do this on a network you trust.",
                )
            },
            confirmButton = {
                TvMarqueeButton(
                    text = "Connect",
                    onClick = viewModel::confirmCleartextConnection,
                    enabled = !state.isLoading,
                )
            },
            dismissButton = {
                TvMarqueeButton(
                    text = "Cancel",
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
                SetupPage.Phone -> PhoneFirst(onEnterAddress = { enteringAddress = true })
                SetupPage.Address -> AddressEntry(
                    state = state,
                    onServerUrlChanged = viewModel::onServerUrlChanged,
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
    return remember(context) {
        // DEVICE_NAME arrived in API 25; older TVs show the generic name.
        val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } else {
            null
        }
        named?.takeIf { it.isNotBlank() } ?: "Android TV"
    }
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
private fun PhoneFirst(onEnterAddress: () -> Unit) {
    val enterAddressFocus = remember { FocusRequester() }
    var enterAddressFocused by remember { mutableStateOf(false) }
    ClaimInitialFocus(enterAddressFocus, { enterAddressFocused }, "serverSetup.phone")
    TvMarqueeScreen(
        accessory = { TvMarqueeStatusChip(rememberTvName(), icon = Icons.Outlined.Tv) },
        copy = {
            TvMarqueeStatusChip(
                stringResource(R.string.tv_setup_phone_looking),
                showsSpinner = true,
                modifier = Modifier.padding(bottom = 18.dp),
            )
            TvMarqueeHeadline("Set up with\nyour phone")
            TvMarqueeBody(
                "It's the easiest way. There's nothing to type with the remote.",
                size = 16.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                "No phone nearby?",
                color = MarqueeColors.InkTertiary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 32.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                TvMarqueeButton(
                    text = "Enter server address",
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
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    TvHideStockImeOnDispose()
    BackHandler(onBack = onBack)
    val fieldFocus = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    ClaimInitialFocus(fieldFocus, { fieldFocused }, "serverSetup.address")
    val connect = { if (canSubmitTvServerUrl(state.serverUrl, state.isLoading)) onConnect() }
    TvMarqueeScreen(
        copyScroll = rememberTvImeAwareFormScrollState(),
        accessory = { TvMarqueeStatusChip(rememberTvName(), icon = Icons.Outlined.Tv) },
        copy = {
            TvMarqueeHeadline("Enter your\nserver address")
            TvMarqueeBody("Type the address you use for Silo.", modifier = Modifier.padding(top = 11.dp))
            Box(Modifier.width(380.dp).padding(top = 20.dp).tvImeAwareFieldContext()) {
                TvMarqueeField(
                    value = state.serverUrl,
                    onValueChange = onServerUrlChanged,
                    icon = Icons.Outlined.Language,
                    placeholder = "media.example.com",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    onImeAction = connect,
                    isError = state.error != null,
                    focusRequester = fieldFocus,
                    onFocusChange = { fieldFocused = it },
                )
            }
            state.error?.let { TvMarqueeErrorText(it, Modifier.width(380.dp).padding(top = 8.dp)) }
            // Typing aids for the remote's keyboard.
            Row(Modifier.fillMaxWidth().padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ServerUrlShortcuts.forEach { shortcut ->
                    TvMarqueeButton(
                        text = shortcut,
                        onClick = { if (!state.isLoading) onServerUrlChanged(applyServerUrlShortcut(state.serverUrl, shortcut)) },
                        kind = TvMarqueeButtonKind.Glass,
                        compact = true,
                    )
                }
            }
            Text(
                if (state.usesCleartext) {
                    "This address uses unencrypted HTTP. Fine on a trusted home network; avoid it on public Wi-Fi."
                } else {
                    "Secure HTTPS is tried automatically."
                },
                color = if (state.usesCleartext) MarqueeColors.Warning else MarqueeColors.InkTertiary,
                fontSize = 14.sp,
                modifier = Modifier.width(380.dp).padding(top = 9.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                TvMarqueeButton(
                    text = if (state.isLoading) "Connecting…" else "Connect",
                    onClick = connect,
                    isLoading = state.isLoading,
                )
                TvMarqueeButton(
                    text = "Back",
                    onClick = onBack,
                    kind = TvMarqueeButtonKind.Plain,
                )
            }
        },
        card = {
            TvUsePhoneCard("Open Silo on a phone or tablet on the same Wi‑Fi and tap Set up. It finishes this for you.")
        },
    )
}

private val ServerUrlShortcuts = listOf("https://", "http://", ".com")

private fun applyServerUrlShortcut(value: String, shortcut: String): String =
    when {
        shortcut.endsWith("://") &&
            (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) -> value
        shortcut.endsWith("://") -> shortcut + value.trimStart()
        else -> value + shortcut
    }
