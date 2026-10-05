package org.siloserver.silo.tv.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.common.pairing.PairingReceiver
import org.siloserver.silo.common.pairing.PairingReceiverStatus
import org.siloserver.silo.common.pairing.TvPairingAdvertiser
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.marqueeShake
import org.siloserver.silo.common.ui.marquee.SignInHandoff
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeBody
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButton
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButtonKind
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeCard
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeCardSymbol
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeCodeTiles
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeErrorText
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeField
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeHeadline
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeMetrics
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeScreen
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeServerCard
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeStatusChip
import org.siloserver.silo.tv.ui.components.marquee.TvUsePhoneCard
import org.siloserver.silo.tv.ui.components.marquee.rememberTvActiveServer
import org.siloserver.silo.tv.ui.components.rememberTvImeAwareFormScrollState
import org.siloserver.silo.tv.ui.components.tvImeAwareFieldContext
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved

/**
 * TV sign-in: every way in is visible at once — scan the QR, type
 * `<host>/activate` and the code, or open Silo on a nearby phone — with the
 * password one click away. One copy deck with Apple TV (server spec "TV
 * device sign-in UX"). The code renews itself while the screen is visible;
 * there is no countdown. When the TV reached the server through a network
 * provider such as Tailscale, "Continue as <owner>" leads and takes focus:
 * one press signs in, and every other way in stays.
 *
 * TOP-anchored so the username/password fields stay above the on-screen IME:
 * on Android TV the soft keyboard eats the lower half of the viewport.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLoginScreen(
    onLoginSuccess: () -> Unit,
    onCreateAccount: () -> Unit = {},
    onChangeServer: () -> Unit = {},
    signupEnabled: Boolean = false,
    sessionExpired: Boolean = false,
    viewModel: TvLoginViewModel = koinViewModel(parameters = { parametersOf(sessionExpired) }),
    pairingReceiver: PairingReceiver = koinInject(),
    pairingAdvertiser: TvPairingAdvertiser = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val device by viewModel.deviceSignIn.collectAsState()
    val advertisement by viewModel.pairingAdvertisement.collectAsState()
    val pairingStatus by pairingReceiver.status.collectAsState()
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val usePasswordFocus = remember { FocusRequester() }
    val changeServerFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val signInFocus = remember { FocusRequester() }
    val createAccountFocus = remember { FocusRequester() }
    val backToPhoneFocus = remember { FocusRequester() }
    val formChangeServerFocus = remember { FocusRequester() }
    val networkFocus = remember { FocusRequester() }
    val formScrollState = rememberTvImeAwareFormScrollState()

    // Phone-first IA (mirrors tvOS TVLoginView): the device code leads, and the
    // username/password form is one focus-step away. A server without device
    // sign-in goes straight to the form.
    var showPasswordForm by remember { mutableStateOf(false) }
    // An OIDC-only server (local passwords off, no directory) takes no
    // password from this TV: device sign-in alone.
    val passwordFormVisible = (showPasswordForm || device.passwordOnly) && state.passwordAvailable
    // Password sign-in turned off while the form was open (the options are
    // re-read on return to this screen): the TV falls back to the code, and
    // the form doesn't come back by itself if passwords are turned on again.
    LaunchedEffect(state.passwordAvailable) {
        if (!state.passwordAvailable) showPasswordForm = false
    }

    // Stop polling in the background (or under the screensaver); poll at once,
    // renewing the code if it expired, when the screen is visible again.
    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }

    // A signed-out TV advertises st=login with its server's identity so a
    // nearby phone that holds the same server can sign it in (#419). Only
    // while the screen is visible, like polling: nobody can answer the
    // consent prompt of a TV in the background.
    LifecycleStartEffect(advertisement) {
        val token = advertisement?.let(pairingAdvertiser::start)
        onStopOrDispose { token?.let(pairingAdvertiser::stop) }
    }
    val isActivePairing = pairingStatus.isActivePairing

    // The sign-in screen and a nearby phone's session can both finish the
    // same sign-in; route on once.
    var routed by remember { mutableStateOf(false) }
    val routeOnce = {
        if (!routed) {
            routed = true
            // A one-profile household with no PIN goes straight to Home.
            SignInHandoff.skipsSingleProfilePicker = true
            onLoginSuccess()
        }
    }
    LaunchedEffect(pairingStatus) {
        if (pairingStatus is PairingReceiverStatus.Completed) {
            delay(1_800)
            routeOnce()
        }
    }

    LaunchedEffect(state.loginSuccess) {
        if (state.loginSuccess) {
            // A nearby phone that approved this code still has to hear the
            // result and say done; leaving first closes its session, which it
            // reports as a failure. Completed routes on by itself after its
            // dwell. Bounded, so a phone that goes quiet can't hold the TV here.
            val settled = withTimeoutOrNull(NEARBY_RESULT_WAIT_MS) {
                pairingReceiver.status.first {
                    it !is PairingReceiverStatus.AwaitingApproval && it !is PairingReceiverStatus.SignedIn
                }
            }
            // Consumed only now: it is this effect's key, so clearing it
            // before the wait would cancel the wait and the fallback route.
            viewModel.onLoginSuccessConsumed()
            if (settled !is PairingReceiverStatus.Completed) routeOnce()
        }
    }

    // "Use your phone instead" returns to a fresh code, not one that may have
    // gone stale behind the form.
    val backToPhone = {
        showPasswordForm = false
        viewModel.restartDeviceLogin()
    }
    // Back steps out of the password form and the nearby-phone panel instead
    // of leaving the app.
    BackHandler(enabled = passwordFormVisible && !device.passwordOnly && !isActivePairing, onBack = backToPhone)
    BackHandler(enabled = isActivePairing, onBack = pairingReceiver::cancelActiveSession)

    // A server without device sign-in opens on the password form, so
    // "Continue as …" shows there too, above the fields (Apple TV parity).
    // It has its own focus key there, so focus is claimed again when the
    // button moves from the code screen into the form.
    val networkInForm = device.passwordOnly && state.networkProvider != null
    val networkKey = if (passwordFormVisible) "formNetwork" else "network"
    // "Continue as …" is the one-press way in, so it takes focus whenever the
    // server offers it, unless it was refused and the code needs the person:
    // then that recovery action leads again.
    val networkLeads = state.networkProvider != null &&
        (state.networkError == null || !device.status.actionTakesFocus())

    // After "Continue as …", focus moves to the state's action when the code
    // needs the person ("Try again" / "Show a new code"), to "Change server"
    // when only that helps, otherwise to the one local action, "Sign in with a
    // password". "Can't reach" and "Too many requests" keep retrying by
    // themselves, so their "Try again" doesn't take focus (Apple TV parity).
    val focusTarget = when {
        passwordFormVisible -> if (networkInForm && networkLeads) networkKey else "username"
        networkLeads -> networkKey
        device.status.actionTakesFocus() -> "action"
        device.status == TvSignInStatus.UpdateRequired -> "changeServer"
        device.status == TvSignInStatus.Unreachable || device.status == TvSignInStatus.TooManyRequests -> null
        state.passwordAvailable -> "usePassword"
        else -> "changeServer"
    }
    // Which claim target holds focus. Checking "the screen has focus" is not
    // enough: after a state change focus is still on the old button, and the
    // claim must move it to the state's action.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val trackFocus: (String) -> Modifier = { key ->
        Modifier.onFocusChanged { state ->
            if (state.hasFocus) focusedKey = key else if (focusedKey == key) focusedKey = null
        }
    }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(focusTarget, inputMode, isActivePairing) {
        // Touch/mouse exception: nothing is auto-focused for pointer users
        // (product call 2026-08-14) — a programmatic claim on a text field in
        // touch mode pops the IME, and buttons refuse focus in touch mode.
        if (inputMode == InputMode.Touch || isActivePairing || focusTarget == null) {
            TvFocusLog.d { "login: claim skipped (touch=${inputMode == InputMode.Touch}, pairing=$isActivePairing, target=$focusTarget)" }
            return@LaunchedEffect
        }
        val target = when (focusTarget) {
            "username" -> usernameFocus
            "network", "formNetwork" -> networkFocus
            "action" -> actionFocus
            "changeServer" -> changeServerFocus
            else -> usePasswordFocus
        }
        TvFocusLog.d { "login: claiming $focusTarget (mode=$inputMode)" }
        val result = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = target::requestFocus,
            isFocused = { focusedKey == focusTarget },
        )
        TvFocusLog.d { "login: claim result=$result" }
    }

    val changeServer = {
        viewModel.onChangeServer()
        onChangeServer()
    }
    val serverName = state.serverName ?: state.serverHost.orEmpty()
    val network = state.networkProvider?.let { provider ->
        NetworkSignInAction(
            provider = provider,
            busy = state.networkBusy,
            error = state.networkError?.message(providerName = null, externalName = provider.displayName),
            focusRequester = networkFocus,
            focusTracking = trackFocus(networkKey),
            onClick = viewModel::onNetworkSignInClick,
        )
    }

    val server = rememberTvActiveServer()
    LaunchedEffect(Unit) {
        MarqueeScene.focus = MarqueeScene.Focus.Account
        MarqueeScene.personalTint = null
    }
    val displayName = server?.name ?: serverName
    // No code and no password on this server: "Continue as …" stands alone.
    val networkOnly = device.passwordOnly && !state.passwordAvailable && network != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        if (isActivePairing) {
            ActivePairingPanel(
                status = pairingStatus,
                onCancel = pairingReceiver::cancelActiveSession,
                onContinue = routeOnce,
                onAllow = pairingReceiver::allowPendingServer,
                onDeny = pairingReceiver::denyPendingServer,
                onUseAlternate = pairingReceiver::useAlternateAddress,
                onRetryAddress = pairingReceiver::retryPushedAddress,
                signIn = true,
            )
            return@Box
        }

        val notices: @Composable () -> Unit = {
            if (state.sessionExpired && displayName.isNotBlank()) {
                TvMarqueeStatusChip(
                    stringResource(R.string.tv_signin_session_expired, displayName),
                    icon = Icons.Outlined.ErrorOutline,
                    modifier = Modifier.padding(bottom = 15.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // A password refused because password sign-in is off closes the
            // form, the only place its error shows: say it here instead.
            if (state.passwordTurnedOff) {
                TvMarqueeStatusChip(
                    stringResource(R.string.tv_signin_error_local_login_disabled),
                    icon = Icons.Outlined.ErrorOutline,
                    modifier = Modifier.padding(bottom = 15.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            TvMarqueeServerCard(
                name = displayName,
                address = server?.hostLabel ?: state.serverHost.orEmpty(),
                markUrl = server?.branding?.markUrl,
                secure = server?.isSecure ?: false,
            )
        }

        when {
            passwordFormVisible -> PasswordScreen(
                state = state,
                passwordOnly = device.passwordOnly,
                serverName = displayName,
                notices = notices,
                usernameFocus = usernameFocus,
                usernameFocusTracking = trackFocus("username"),
                passwordFocus = passwordFocus,
                signInFocus = signInFocus,
                createAccountFocus = createAccountFocus,
                backToPhoneFocus = backToPhoneFocus,
                changeServerFocus = formChangeServerFocus,
                onUsernameChanged = viewModel::onUsernameChanged,
                onPasswordChanged = viewModel::onPasswordChanged,
                onLoginClick = viewModel::onLoginClick,
                signupEnabled = signupEnabled,
                onCreateAccount = onCreateAccount,
                onBackToPhone = backToPhone,
                onChangeServer = changeServer,
                network = network.takeIf { networkInForm },
            )

            networkOnly -> TvMarqueeScreen(
                copy = {
                    notices()
                    TvMarqueeHeadline(stringResource(R.string.tv_signin_title, displayName), Modifier.padding(top = 22.dp))
                    network?.let { NetworkSignInButton(it, below = changeServerFocus, modifier = Modifier.padding(top = 20.dp)) }
                    TvMarqueeButton(
                        text = stringResource(R.string.tv_signin_action_change_server),
                        onClick = changeServer,
                        kind = TvMarqueeButtonKind.Plain,
                        focusRequester = changeServerFocus,
                        modifier = Modifier
                            .padding(top = 15.dp)
                            .then(trackFocus("changeServer"))
                            .focusProperties { network?.let { up = it.focusRequester } },
                    )
                },
                card = {
                    TvMarqueeCard {
                        TvMarqueeCardSymbol(Icons.Outlined.Lan)
                        Text(
                            "No code needed",
                            color = MarqueeColors.Ink,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 17.dp),
                        )
                        TvMarqueeBody(
                            "This TV reached $displayName through ${network?.provider?.displayName ?: "its network"}, " +
                                "which knows who it belongs to.",
                            size = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                },
            )

            else -> TvMarqueeScreen(
                copy = {
                    notices()
                    TvMarqueeHeadline(stringResource(R.string.tv_signin_title, displayName), Modifier.padding(top = 22.dp))
                    if (network != null) {
                        NetworkSignInButton(
                            action = network,
                            // The copy between holds nothing focusable: name the way down.
                            below = codeActionBelowNetwork(device.status, state.passwordAvailable, actionFocus, usePasswordFocus, changeServerFocus),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            "Or sign in with your phone",
                            color = MarqueeColors.InkTertiary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 15.dp),
                        )
                    }
                    val activate = device.code?.activateText ?: state.serverActivateText.orEmpty()
                    TvMarqueeBody(
                        "Scan the code with your phone's camera, or go to " +
                            (activate.ifBlank { "your server's /activate page" }) +
                            " and enter it. Approve on your phone and this TV signs in by itself.",
                        modifier = Modifier.padding(top = if (network == null) 13.dp else 6.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = MarqueeColors.InkTertiary, modifier = Modifier.size(13.dp))
                        Text(stringResource(R.string.tv_signin_nearby_hint), color = MarqueeColors.InkTertiary, fontSize = 14.sp)
                    }
                    CodeActions(
                        status = device.status,
                        showPasswordAction = state.passwordAvailable,
                        network = network,
                        actionFocus = actionFocus,
                        actionTracking = trackFocus("action"),
                        usePasswordFocus = usePasswordFocus,
                        usePasswordTracking = trackFocus("usePassword"),
                        changeServerFocus = changeServerFocus,
                        changeServerTracking = trackFocus("changeServer"),
                        onAction = viewModel::restartDeviceLogin,
                        // The form would hide "Signing in…" while "Continue as …"
                        // waits, and its Sign in would do nothing until then.
                        onUsePassword = { if (!state.networkBusy) showPasswordForm = true },
                        onChangeServer = changeServer,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                },
                card = { TvMarqueeCard { CodeCard(device = device, serverHost = state.serverHost.orEmpty()) } },
            )
        }
    }
}

/** Where Down from "Continue as …" lands on the code screen: the first local action. */
private fun codeActionBelowNetwork(
    status: TvSignInStatus,
    passwordAvailable: Boolean,
    actionFocus: FocusRequester,
    usePasswordFocus: FocusRequester,
    changeServerFocus: FocusRequester,
): FocusRequester = when {
    status.action() != null -> actionFocus
    passwordAvailable && status != TvSignInStatus.UpdateRequired -> usePasswordFocus
    else -> changeServerFocus
}

/** The "Try again" / "Show a new code" action a status offers, if any. */
private fun TvSignInStatus.action(): Int? = when (this) {
    TvSignInStatus.CouldntFinish,
    TvSignInStatus.Failed,
    TvSignInStatus.Unreachable,
    TvSignInStatus.TooManyRequests,
    -> R.string.tv_signin_action_try_again
    TvSignInStatus.Denied,
    TvSignInStatus.Paused,
    -> R.string.tv_signin_action_new_code
    else -> null
}

/** States that wait for the person, so their action takes focus. */
private fun TvSignInStatus.actionTakesFocus(): Boolean = when (this) {
    TvSignInStatus.CouldntFinish,
    TvSignInStatus.Denied,
    TvSignInStatus.Paused,
    TvSignInStatus.Failed,
    -> true
    else -> false
}

/**
 * "Continue as <owner>" through a network provider, for the code screen and,
 * on a server without device sign-in, the password screen. [error] is why the
 * last press was refused.
 */
private class NetworkSignInAction(
    val provider: SignInProvider,
    val busy: Boolean,
    val error: String?,
    val focusRequester: FocusRequester,
    val focusTracking: Modifier,
    val onClick: () -> Unit,
)

/**
 * The state's action ("Try again" / "Show a new code"), "Sign in with a
 * password" and "Change server" on one row, as on Apple TV. Controls stay
 * enabled while work runs so focus doesn't drop; the actions ignore repeats.
 */
@Composable
private fun CodeActions(
    status: TvSignInStatus,
    showPasswordAction: Boolean,
    network: NetworkSignInAction?,
    actionFocus: FocusRequester,
    actionTracking: Modifier,
    usePasswordFocus: FocusRequester,
    usePasswordTracking: Modifier,
    changeServerFocus: FocusRequester,
    changeServerTracking: Modifier,
    onAction: () -> Unit,
    onUsePassword: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val upTo = network?.focusRequester
    // An update requirement hides the password button: no password can help.
    val offersPassword = showPasswordAction && status != TvSignInStatus.UpdateRequired
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        status.action()?.let { label ->
            TvMarqueeButton(
                text = stringResource(label),
                onClick = onAction,
                icon = Icons.Filled.Refresh,
                focusRequester = actionFocus,
                modifier = Modifier
                    .then(actionTracking)
                    .focusProperties { upTo?.let { up = it } },
            )
        }
        if (offersPassword) {
            TvMarqueeButton(
                text = stringResource(R.string.tv_signin_action_password),
                onClick = onUsePassword,
                kind = TvMarqueeButtonKind.Glass,
                icon = Icons.Outlined.Key,
                focusRequester = usePasswordFocus,
                modifier = Modifier
                    .then(usePasswordTracking)
                    .focusProperties {
                        right = changeServerFocus
                        upTo?.let { up = it }
                    },
            )
        }
        TvMarqueeButton(
            text = stringResource(R.string.tv_signin_action_change_server),
            onClick = onChangeServer,
            kind = TvMarqueeButtonKind.Plain,
            focusRequester = changeServerFocus,
            modifier = Modifier
                .then(changeServerTracking)
                .focusProperties {
                    if (offersPassword) left = usePasswordFocus
                    upTo?.let { up = it }
                },
        )
    }
}

/**
 * "Continue as <owner>" with "via <provider>" under it, or "Continue with
 * <provider>" when the provider named nobody: the screen's primary action
 * when offered, with its refusal under it. It stays focusable while signing
 * in so focus doesn't jump; the view model ignores a second press.
 */
@Composable
private fun NetworkSignInButton(action: NetworkSignInAction, below: FocusRequester, modifier: Modifier = Modifier) {
    val providerName = action.provider.displayName
    val owner = action.provider.networkIdentity?.label
    val title = when {
        action.busy -> stringResource(R.string.tv_signin_form_submitting)
        owner != null -> stringResource(R.string.tv_signin_network_continue_as, owner)
        else -> stringResource(R.string.tv_signin_network_continue_with, providerName)
    }
    val via = owner?.takeIf { !action.busy }?.let { stringResource(R.string.tv_signin_network_via, providerName) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        TvMarqueeButton(
            onClick = action.onClick,
            isLoading = action.busy,
            focusRequester = action.focusRequester,
            modifier = Modifier
                .height(if (via != null) 52.dp else TvMarqueeMetrics.ButtonHeight)
                .then(action.focusTracking)
                .focusProperties { down = below },
        ) { ink ->
            if (!action.busy) TvProviderMark(action.provider)
            Column {
                Text(title, color = ink, maxLines = 1)
                if (via != null) Text(via, color = ink.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        action.error?.let { error ->
            TvMarqueeErrorText(error, Modifier.width(380.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** A provider's initial on a warm gradient, beside "Continue as …". */
@Composable
private fun TvProviderMark(provider: SignInProvider) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFD4B2D), Color(0xFFA21D5C)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(provider.displayName.firstOrNull()?.lowercase() ?: "s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/**
 * The card: the QR (dark on white, quiet zone), the code as tiles, the
 * typed-URL form, and a polite live status line. No countdown: codes renew
 * in place.
 */
@Composable
private fun CodeCard(device: TvDeviceSignInUi, serverHost: String) {
    val code = device.code
    when {
        code != null -> {
            val qrHost = code.activateText.removeSuffix("/activate")
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(10.dp),
            ) {
                QrCodePanel(
                    content = code.qrContent,
                    size = QrSize,
                    description = stringResource(R.string.tv_signin_qr_description, qrHost, code.spokenCode),
                )
            }
            TvMarqueeCodeTiles(code = code.userCode, modifier = Modifier.padding(top = 17.dp))
            Text(
                code.activateText,
                color = MarqueeColors.InkSecondary,
                fontSize = 14.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
        device.status == TvSignInStatus.SignedIn -> TvMarqueeCardSymbol(Icons.Filled.Check, tint = MarqueeColors.Live, size = 70.dp)
        device.status.isProblem() -> TvMarqueeCardSymbol(Icons.Outlined.ErrorOutline, tint = MarqueeColors.Warning)
        else -> Box(
            Modifier
                .size(QrSize + 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (device.status == TvSignInStatus.GettingCode) {
                CircularProgressIndicator(color = Color.Black.copy(alpha = 0.5f), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (device.status == TvSignInStatus.Waiting || device.status == TvSignInStatus.Opened || device.status == TvSignInStatus.GettingCode) {
            CircularProgressIndicator(color = MarqueeColors.InkSecondary, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
        }
        Text(
            device.statusText(serverHost),
            color = if (device.status.isProblem()) MarqueeColors.Error else MarqueeColors.InkSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun TvSignInStatus.isProblem(): Boolean = when (this) {
    TvSignInStatus.CouldntFinish,
    TvSignInStatus.Denied,
    TvSignInStatus.Unreachable,
    TvSignInStatus.TooManyRequests,
    TvSignInStatus.Failed,
    TvSignInStatus.UpdateRequired,
    -> true
    else -> false
}

@Composable
private fun TvDeviceSignInUi.statusText(serverHost: String): String = when (status) {
    TvSignInStatus.GettingCode -> stringResource(R.string.tv_signin_status_getting_code)
    TvSignInStatus.Waiting -> stringResource(R.string.tv_signin_status_waiting)
    TvSignInStatus.Opened -> stringResource(R.string.tv_signin_status_opened)
    TvSignInStatus.NewCode -> stringResource(R.string.tv_signin_status_new_code)
    TvSignInStatus.Unreachable -> stringResource(R.string.tv_signin_status_unreachable, serverHost)
    TvSignInStatus.TooManyRequests -> stringResource(R.string.tv_signin_status_too_many)
    TvSignInStatus.SignedIn -> accountName?.let { stringResource(R.string.tv_signin_status_signed_in_as, it) }
        ?: stringResource(R.string.tv_signin_status_signed_in)
    TvSignInStatus.CouldntFinish -> stringResource(R.string.tv_signin_status_couldnt_finish)
    TvSignInStatus.Denied -> stringResource(R.string.tv_signin_status_denied)
    TvSignInStatus.Paused -> stringResource(R.string.tv_signin_status_paused)
    TvSignInStatus.Failed -> stringResource(R.string.tv_signin_status_failed)
    TvSignInStatus.UpdateRequired -> stringResource(R.string.tv_signin_status_update_required)
}

/**
 * [providerName] is the server's browser sign-in provider: an account that
 * signs in with it has no password here, so a refused password points to the
 * phone. [externalName] names the provider the refused attempt went through:
 * the directory (LDAP) the form also reaches, or the network provider behind
 * "Continue as …".
 */
@Composable
private fun TvLoginError.message(providerName: String?, externalName: String?): String {
    val external = externalName ?: stringResource(R.string.tv_signin_provider_fallback)
    return when (this) {
        TvLoginError.UsernameRequired -> stringResource(R.string.tv_signin_error_username_required)
        TvLoginError.PasswordRequired -> stringResource(R.string.tv_signin_error_password_required)
        TvLoginError.InvalidCredentials -> if (providerName != null) {
            stringResource(R.string.tv_signin_error_invalid_credentials_provider, providerName)
        } else {
            stringResource(R.string.tv_signin_error_invalid_credentials)
        }
        TvLoginError.EmailInUse -> stringResource(R.string.tv_signin_error_email_in_use, external)
        TvLoginError.IdentityLinkedElsewhere -> stringResource(R.string.tv_signin_error_identity_linked_elsewhere, external)
        TvLoginError.RateLimited -> stringResource(R.string.tv_signin_error_rate_limited)
        TvLoginError.AccountRequired -> stringResource(R.string.tv_signin_error_account_required)
        TvLoginError.AccountDisabled -> stringResource(R.string.tv_signin_error_account_disabled)
        TvLoginError.Network -> stringResource(R.string.tv_signin_error_network)
        TvLoginError.LocalLoginDisabled -> stringResource(R.string.tv_signin_error_local_login_disabled)
        TvLoginError.NotPermitted -> stringResource(R.string.tv_signin_error_not_permitted)
        TvLoginError.PasswordExpired -> stringResource(R.string.tv_signin_error_password_expired)
        TvLoginError.ProviderUnavailable -> stringResource(R.string.tv_signin_error_provider_unavailable)
        TvLoginError.IdentityChanged -> stringResource(R.string.tv_signin_error_identity_changed)
        TvLoginError.SaveFailed -> stringResource(R.string.tv_signin_error_save_failed)
        TvLoginError.CleanupIncomplete -> stringResource(R.string.tv_signin_error_cleanup_incomplete)
        TvLoginError.NetworkIdentityRequired -> stringResource(R.string.tv_signin_error_network_identity_required, external)
        TvLoginError.NetworkNotPermitted -> stringResource(R.string.tv_signin_error_network_not_permitted, external)
        TvLoginError.NetworkEmailInUse -> stringResource(R.string.tv_signin_error_network_email_in_use, external)
        TvLoginError.NetworkProviderGone -> stringResource(R.string.tv_signin_error_network_provider_gone, external)
        is TvLoginError.Server -> message ?: stringResource(R.string.tv_signin_error_login_failed)
    }
}

/**
 * Username and password on the left, laid out like Apple TV's password
 * screen; the card beside them says a nearby phone can sign in instead.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PasswordScreen(
    state: TvLoginUiState,
    passwordOnly: Boolean,
    serverName: String,
    notices: @Composable () -> Unit,
    usernameFocus: FocusRequester,
    usernameFocusTracking: Modifier,
    passwordFocus: FocusRequester,
    signInFocus: FocusRequester,
    createAccountFocus: FocusRequester,
    backToPhoneFocus: FocusRequester,
    changeServerFocus: FocusRequester,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLoginClick: () -> Unit,
    signupEnabled: Boolean,
    onCreateAccount: () -> Unit,
    onBackToPhone: () -> Unit,
    onChangeServer: () -> Unit,
    network: NetworkSignInAction? = null,
) {
    // Each rejected password shakes the fields and puts focus back on the
    // password: the fields were disabled while it was checked, so focus left.
    var failures by remember { mutableIntStateOf(0) }
    var passwordFocused by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.error) {
        if (state.error != null && state.password.isNotEmpty()) {
            failures++
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = passwordFocus::requestFocus,
                isFocused = { passwordFocused },
            )
        }
    }
    TvMarqueeScreen(
        copyScroll = rememberTvImeAwareFormScrollState(),
        copy = {
            Column {
                notices()
                TvMarqueeHeadline("Sign in with\na password", Modifier.padding(top = 22.dp))
                TvMarqueeBody(
                    if (passwordOnly && network == null) {
                        stringResource(R.string.tv_signin_password_only)
                    } else {
                        "Use your $serverName username and password."
                    },
                    modifier = Modifier.padding(top = 11.dp),
                )
                val firstSecondary = when {
                    signupEnabled -> createAccountFocus
                    !passwordOnly -> backToPhoneFocus
                    else -> changeServerFocus
                }
                // While either sign-in waits, the fields and Sign in are disabled and
                // can't take focus: "Continue as …" (which keeps focus while it waits)
                // and the secondary actions reach each other directly.
                val aboveSecondary = if (state.signingIn && network != null) network.focusRequester else signInFocus
                if (network != null) {
                    NetworkSignInButton(
                        action = network,
                        below = if (state.signingIn) firstSecondary else usernameFocus,
                        modifier = Modifier.padding(top = 15.dp),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .width(380.dp)
                        .padding(top = 20.dp)
                        .marqueeShake(failures),
                ) {
                    Box(Modifier.tvImeAwareFieldContext()) {
                        TvMarqueeField(
                            value = state.username,
                            onValueChange = onUsernameChanged,
                            icon = Icons.Outlined.Person,
                            placeholder = stringResource(R.string.tv_signin_form_username_placeholder),
                            enabled = !state.signingIn,
                            focusRequester = usernameFocus,
                            modifier = Modifier
                                .semantics { contentType = ContentType.Username }
                                .then(usernameFocusTracking)
                                .focusProperties { if (network != null) up = network.focusRequester },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).tvImeAwareFieldContext()) {
                            TvMarqueeField(
                                value = state.password,
                                onValueChange = onPasswordChanged,
                                icon = Icons.Outlined.Lock,
                                placeholder = stringResource(R.string.tv_signin_form_password_placeholder),
                                isSecure = true,
                                revealed = passwordVisible,
                                isError = state.error != null,
                                imeAction = ImeAction.Done,
                                onImeAction = {
                                    if (canSubmitTvCredentialLogin(state.username, state.password, state.signingIn)) onLoginClick()
                                },
                                enabled = !state.signingIn,
                                focusRequester = passwordFocus,
                                onFocusChange = { passwordFocused = it },
                                modifier = Modifier.semantics { contentType = ContentType.Password },
                            )
                        }
                        TvMarqueeButton(
                            onClick = { passwordVisible = !passwordVisible },
                            kind = TvMarqueeButtonKind.Glass,
                            enabled = !state.signingIn,
                            modifier = Modifier.semantics {
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            },
                        ) { ink ->
                            Icon(
                                if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                                tint = ink,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                state.error?.let { error ->
                    TvMarqueeErrorText(
                        error.message(state.providerName, state.directoryName),
                        Modifier
                            .width(380.dp)
                            .padding(top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                // An account that signs in with the server's provider has no password
                // here (silo-apple parity). The 401 message says the same, so the hint
                // steps aside while that message shows.
                state.providerName?.takeIf { state.error != TvLoginError.InvalidCredentials }?.let { provider ->
                    TvMarqueeBody(
                        stringResource(R.string.tv_signin_form_provider_hint, provider),
                        size = 14.sp,
                        color = MarqueeColors.InkTertiary,
                        modifier = Modifier.width(380.dp).padding(top = 8.dp),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    TvMarqueeButton(
                        text = stringResource(if (state.isLoading) R.string.tv_signin_form_submitting else R.string.tv_signin_form_submit),
                        onClick = onLoginClick,
                        isLoading = state.isLoading,
                        focusRequester = signInFocus,
                        enabled = !state.signingIn,
                        modifier = Modifier.focusProperties { down = firstSecondary },
                    )
                    if (signupEnabled) {
                        TvMarqueeButton(
                            text = stringResource(R.string.tv_signin_form_create_account),
                            onClick = onCreateAccount,
                            kind = TvMarqueeButtonKind.Plain,
                            focusRequester = createAccountFocus,
                            modifier = Modifier.focusProperties { up = aboveSecondary },
                        )
                    }
                    if (!passwordOnly) {
                        // Back to the device code (a fresh one if the old one lapsed).
                        TvMarqueeButton(
                            text = stringResource(R.string.tv_signin_form_use_phone),
                            onClick = onBackToPhone,
                            kind = TvMarqueeButtonKind.Plain,
                            focusRequester = backToPhoneFocus,
                            modifier = Modifier.focusProperties { up = aboveSecondary },
                        )
                    }
                    TvMarqueeButton(
                        text = stringResource(R.string.tv_signin_action_change_server),
                        onClick = onChangeServer,
                        kind = TvMarqueeButtonKind.Plain,
                        focusRequester = changeServerFocus,
                        modifier = Modifier.focusProperties { up = aboveSecondary },
                    )
                }
            }
        },
        card = {
            TvUsePhoneCard(
                if (passwordOnly) {
                    "Signed in to Silo on your phone? Open it on the same Wi‑Fi and it can sign this TV in for you."
                } else {
                    "Choose \"Use your phone instead\" to scan a code and approve on your phone. Nothing to type with the remote."
                },
            )
        },
    )
}

/** The QR, at least a third of the 540dp reference surface with its frame. */
private val QrSize: Dp = 170.dp

/** How long a finished sign-in waits for a nearby phone's session to wrap up before routing on. */
private const val NEARBY_RESULT_WAIT_MS = 5_000L
