package org.siloserver.silo.tv.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
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
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.AuroraEyebrow
import org.siloserver.silo.tv.ui.components.AuroraGhostButton
import org.siloserver.silo.tv.ui.components.AuroraJourneyProgress
import org.siloserver.silo.tv.ui.components.AuroraPrimaryButton
import org.siloserver.silo.tv.ui.components.AuroraStepRow
import org.siloserver.silo.tv.ui.components.TvAuroraBackdrop
import org.siloserver.silo.tv.ui.components.TvAuroraVariant
import org.siloserver.silo.tv.ui.components.TvAuthFormDefaults
import org.siloserver.silo.tv.ui.components.auroraGlass
import org.siloserver.silo.tv.ui.components.auroraPanel
import org.siloserver.silo.tv.ui.components.rememberTvImeAwareFormScrollState
import org.siloserver.silo.tv.ui.components.tvImeAwareFieldContext
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.components.tvShowImeOnSelect
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.Spacing

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TvAuroraBackdrop(variant = TvAuroraVariant.SignIn)

        if (isActivePairing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                ActivePairingPanel(
                    status = pairingStatus,
                    onCancel = pairingReceiver::cancelActiveSession,
                    onContinue = routeOnce,
                    onAllow = pairingReceiver::allowPendingServer,
                    onDeny = pairingReceiver::denyPendingServer,
                    onUseAlternate = pairingReceiver::useAlternateAddress,
                    onRetryAddress = pairingReceiver::retryPushedAddress,
                    signIn = true,
                    modifier = Modifier.widthIn(max = 440.dp),
                )
            }
            return@Box
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                // The scroll is an IME/odd-surface safety valve only. At the
                // reference TV surface (1920x1080 @ 320dpi = 960x540dp) BOTH
                // branches must measure shorter than the viewport, because a
                // scrolled column takes the header chrome off the top with no
                // D-pad way back.
                .verticalScroll(formScrollState)
                .padding(
                    top = Spacing.safeAreaVertical,
                    bottom = Spacing.safeAreaVertical,
                    start = 54.dp,
                    end = 54.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandHeader()
                AuroraJourneyProgress(
                    currentStep = 2,
                    modifier = Modifier.width(215.dp),
                )
            }

            if (state.sessionExpired && serverName.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                SessionExpiredBanner(serverName)
            }
            // A password refused because password sign-in is off closes the
            // form, the only place its error shows: say it here instead.
            if (state.passwordTurnedOff) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                StatusBanner(stringResource(R.string.tv_signin_error_local_login_disabled))
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            if (passwordFormVisible) {
                AuroraEyebrow(
                    text = stringResource(R.string.tv_signin_eyebrow),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                CredentialFormCard(
                    state = state,
                    passwordOnly = device.passwordOnly,
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
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(520.dp),
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SignInHero(
                        serverName = serverName,
                        activateText = device.code?.activateText ?: state.serverActivateText.orEmpty(),
                        usePasswordFocus = usePasswordFocus,
                        changeServerFocus = changeServerFocus,
                        usePasswordTracking = trackFocus("usePassword"),
                        changeServerTracking = trackFocus("changeServer"),
                        // The form would hide "Signing in…" while "Continue as …"
                        // waits, and its Sign in would do nothing until then.
                        onUsePassword = { if (!state.networkBusy) showPasswordForm = true },
                        onChangeServer = changeServer,
                        showPasswordAction = state.passwordAvailable,
                        network = network,
                        modifier = Modifier.weight(1f),
                    )
                    DeviceCodePanel(
                        device = device,
                        serverHost = state.serverHost.orEmpty(),
                        actionFocus = actionFocus,
                        actionTracking = trackFocus("action"),
                        onAction = viewModel::restartDeviceLogin,
                    )
                }
            }
        }
    }
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

@Composable
private fun SessionExpiredBanner(serverName: String) =
    StatusBanner(stringResource(R.string.tv_signin_session_expired, serverName))

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusBanner(text: String) {
    Text(
        text = text,
        style = TvLoginTextStyles.Body,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .auroraGlass(12.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * "Continue as <owner>" through a network provider, for [SignInHero] and, on
 * a server without device sign-in, [CredentialFormCard]. [error] is why the
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
 * Left column: title, "Continue as <owner>" when a network provider offers
 * it, the three ways in, the nearby-phone hint, and the two local actions.
 * Mirrors tvOS `TVLoginView.heroColumn`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SignInHero(
    serverName: String,
    activateText: String,
    usePasswordFocus: FocusRequester,
    changeServerFocus: FocusRequester,
    usePasswordTracking: Modifier,
    changeServerTracking: Modifier,
    onUsePassword: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
    showPasswordAction: Boolean = true,
    network: NetworkSignInAction? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier,
    ) {
        AuroraEyebrow(text = stringResource(R.string.tv_signin_eyebrow))
        Text(
            text = stringResource(R.string.tv_signin_title, serverName),
            style = TvLoginTextStyles.Hero,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (network != null) {
            NetworkSignInButton(
                action = network,
                // The steps between hold nothing focusable: name the way down.
                below = if (showPasswordAction) usePasswordFocus else changeServerFocus,
            )
        }
        AuroraStepRow(number = 1, text = stringResource(R.string.tv_signin_step_scan))
        AuroraStepRow(number = 2, text = stringResource(R.string.tv_signin_step_url, activateText))
        AuroraStepRow(number = 3, text = stringResource(R.string.tv_signin_step_approve))
        Text(
            text = stringResource(R.string.tv_signin_nearby_hint),
            style = TvLoginTextStyles.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (showPasswordAction) {
                AuroraGhostButton(
                    label = stringResource(R.string.tv_signin_action_password),
                    onClick = onUsePassword,
                    fontSize = 17.sp,
                    horizontalPadding = 16.dp,
                    verticalPadding = 8.dp,
                    modifier = Modifier
                        .then(usePasswordTracking)
                        .focusRequester(usePasswordFocus)
                        .focusProperties {
                            right = changeServerFocus
                            if (network != null) up = network.focusRequester
                        },
                )
            }
            AuroraGhostButton(
                label = stringResource(R.string.tv_signin_action_change_server),
                onClick = onChangeServer,
                fontSize = 17.sp,
                horizontalPadding = 16.dp,
                verticalPadding = 8.dp,
                modifier = Modifier
                    .then(changeServerTracking)
                    .focusRequester(changeServerFocus)
                    .focusProperties {
                        if (showPasswordAction) left = usePasswordFocus
                        if (network != null) up = network.focusRequester
                    },
            )
        }
    }
}

/**
 * "Continue as <owner>" with "via <provider>" under it, or "Continue with
 * <provider>" when the provider named nobody: the screen's primary action
 * when offered, with its refusal under it. It stays focusable while signing
 * in so focus doesn't jump; the view model ignores a second press.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkSignInButton(action: NetworkSignInAction, below: FocusRequester) {
    val providerName = action.provider.displayName
    val owner = action.provider.networkIdentity?.label
    AuroraPrimaryButton(
        label = when {
            action.busy -> stringResource(R.string.tv_signin_form_submitting)
            owner != null -> stringResource(R.string.tv_signin_network_continue_as, owner)
            else -> stringResource(R.string.tv_signin_network_continue_with, providerName)
        },
        supportingText = owner?.let { stringResource(R.string.tv_signin_network_via, providerName) },
        onClick = action.onClick,
        focusRequester = action.focusRequester,
        modifier = Modifier
            .then(action.focusTracking)
            .focusProperties { down = below },
    )
    action.error?.let { error ->
        Text(
            text = error,
            style = TvLoginTextStyles.Error,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Right panel: the QR (at least a third of the screen height, dark on white,
 * 4-module quiet zone), the code in large monospace grouped 4+4, the typed-URL
 * form, and a polite live status line. No countdown: codes renew in place.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DeviceCodePanel(
    device: TvDeviceSignInUi,
    serverHost: String,
    actionFocus: FocusRequester,
    actionTracking: Modifier,
    onAction: () -> Unit,
) {
    val qrSize = maxOf(MinQrSize, (LocalConfiguration.current.screenHeightDp / 3).dp)
    val code = device.code
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .width(qrSize + 72.dp)
            .auroraGlass(15.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        if (code != null) {
            val qrHost = code.activateText.removeSuffix("/activate")
            QrCodePanel(
                content = code.qrContent,
                size = qrSize,
                description = stringResource(R.string.tv_signin_qr_description, qrHost, code.spokenCode),
            )
            SignInCodeText(
                code = code.userCode,
                spokenCode = code.spokenCode,
                style = TvSignInCodeStyle,
            )
            Text(
                text = code.activateText,
                style = TvLoginTextStyles.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(qrSize)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp)),
            ) {
                if (device.status == TvSignInStatus.GettingCode) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.7f),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }

        Text(
            text = device.statusText(serverHost),
            style = TvLoginTextStyles.Status,
            color = if (device.status.isProblem()) MaterialTheme.colorScheme.error else Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        device.status.action()?.let { label ->
            // Ghost style at a compact size so "Show a new code" fits the
            // panel on one line; focus fills it like the other actions.
            AuroraGhostButton(
                label = stringResource(label),
                onClick = onAction,
                fontSize = 18.sp,
                horizontalPadding = 14.dp,
                verticalPadding = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(actionTracking)
                    .focusRequester(actionFocus),
            )
        }
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
 * The sign-in code as ONE element TalkBack reads character by character
 * (`TtsSpan.TYPE_VERBATIM`), so `4821 7730` is never read as a number.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalTextApi::class)
@Composable
internal fun SignInCodeText(
    code: String,
    spokenCode: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val annotated = remember(code) {
        buildAnnotatedString {
            withAnnotation(VerbatimTtsAnnotation(DeviceCodeFormat.normalize(code))) { append(code) }
        }
    }
    val description = label?.let { "$it, $spokenCode" }
    Text(
        text = annotated,
        style = style,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics(mergeDescendants = true) {
            if (description != null) contentDescription = description
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Image(
            painter = painterResource(id = R.drawable.silo_wordmark),
            contentDescription = "Silo",
            modifier = Modifier
                .width(66.dp)
                .height(35.dp),
            contentScale = ContentScale.Fit,
        )
    }
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

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun CredentialFormCard(
    state: TvLoginUiState,
    passwordOnly: Boolean,
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
    modifier: Modifier = Modifier,
    network: NetworkSignInAction? = null,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    // Height budget, not taste: this card plus the screen chrome above it has
    // to measure under 540dp (the 1920x1080 @ 320dpi TV surface) with the 24dp
    // overscan inset intact, or the root Column starts scrolling and the header
    // chrome leaves the screen unreachably.
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .auroraPanel(20.dp)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_signin_form_title),
            style = TvLoginTextStyles.Title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (passwordOnly && network == null) {
            Text(
                text = stringResource(R.string.tv_signin_password_only),
                style = TvLoginTextStyles.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            NetworkSignInButton(action = network, below = if (state.signingIn) firstSecondary else usernameFocus)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.tvImeAwareFieldContext(),
        ) {
            Text(
                text = stringResource(R.string.tv_signin_form_username),
                style = TvLoginTextStyles.InputLabel,
                color = Color.White.copy(alpha = 0.52f),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChanged,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    showKeyboardOnFocus = false,
                ),
                enabled = !state.signingIn,
                textStyle = TvLoginTextStyles.Field,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TvAuthFormDefaults.FieldHeight)
                    .semantics { contentType = ContentType.Username }
                    .then(usernameFocusTracking)
                    .tvShowImeOnSelect()
                    .focusRequester(usernameFocus)
                    .focusProperties { if (network != null) up = network.focusRequester },
                colors = tvOutlinedTextFieldColors(),
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.tvImeAwareFieldContext(),
        ) {
            Text(
                text = stringResource(R.string.tv_signin_form_password),
                style = TvLoginTextStyles.InputLabel,
                color = Color.White.copy(alpha = 0.52f),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChanged,
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !state.signingIn,
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (passwordVisible) R.string.tv_signin_form_hide_password else R.string.tv_signin_form_show_password,
                            ),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    showKeyboardOnFocus = false,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canSubmitTvCredentialLogin(state.username, state.password, state.signingIn)) {
                            onLoginClick()
                        }
                    },
                ),
                enabled = !state.signingIn,
                textStyle = TvLoginTextStyles.Field,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TvAuthFormDefaults.FieldHeight)
                    .semantics { contentType = ContentType.Password }
                    .tvShowImeOnSelect()
                    .focusRequester(passwordFocus),
                colors = tvOutlinedTextFieldColors(),
            )
        }

        state.error?.let { error ->
            Text(
                text = error.message(state.providerName, state.directoryName),
                style = TvLoginTextStyles.Error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // An account that signs in with the server's provider has no password
        // here (silo-apple parity). The 401 message says the same, so the hint
        // steps aside while that message shows.
        state.providerName?.takeIf { state.error != TvLoginError.InvalidCredentials }?.let { provider ->
            Text(
                text = stringResource(R.string.tv_signin_form_provider_hint, provider),
                style = TvLoginTextStyles.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AuroraPrimaryButton(
            label = stringResource(if (state.isLoading) R.string.tv_signin_form_submitting else R.string.tv_signin_form_submit),
            icon = Icons.AutoMirrored.Filled.Login,
            onClick = onLoginClick,
            focusRequester = signInFocus,
            focusHalo = false,
            filledAtRest = false,
            neutralFocusFill = true,
            enabled = !state.signingIn,
            modifier = Modifier
                // Explicit chain: the label Texts are not focusable, so there
                // is no default search to fall back on.
                .focusProperties { down = firstSecondary }
                .fillMaxWidth()
                .height(TvAuthFormDefaults.PrimaryButtonHeight),
        )

        // Secondary actions on ONE row, not stacked: three full-width ghost
        // buttons would cost ~120dp of the 540dp viewport.
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (signupEnabled) {
                AuroraGhostButton(
                    label = stringResource(R.string.tv_signin_form_create_account),
                    onClick = onCreateAccount,
                    fontSize = TvLoginSecondaryActionFontSize,
                    horizontalPadding = TvLoginSecondaryActionPadding,
                    verticalPadding = 8.dp,
                    modifier = Modifier
                        .focusRequester(createAccountFocus)
                        .focusProperties {
                            up = aboveSecondary
                            right = if (passwordOnly) changeServerFocus else backToPhoneFocus
                        }
                        .weight(1f),
                )
            }
            if (!passwordOnly) {
                // Back to the device code (a fresh one if the old one lapsed).
                AuroraGhostButton(
                    label = stringResource(R.string.tv_signin_form_use_phone),
                    onClick = onBackToPhone,
                    fontSize = TvLoginSecondaryActionFontSize,
                    horizontalPadding = TvLoginSecondaryActionPadding,
                    verticalPadding = 8.dp,
                    modifier = Modifier
                        .focusRequester(backToPhoneFocus)
                        .focusProperties {
                            up = aboveSecondary
                            if (signupEnabled) left = createAccountFocus
                            right = changeServerFocus
                        }
                        .weight(1f),
                )
            }
            AuroraGhostButton(
                label = stringResource(R.string.tv_signin_action_change_server),
                onClick = onChangeServer,
                fontSize = TvLoginSecondaryActionFontSize,
                horizontalPadding = TvLoginSecondaryActionPadding,
                verticalPadding = 8.dp,
                modifier = Modifier
                    .focusRequester(changeServerFocus)
                    .focusProperties {
                        up = aboveSecondary
                        left = when {
                            !passwordOnly -> backToPhoneFocus
                            signupEnabled -> createAccountFocus
                            else -> signInFocus
                        }
                    }
                    .weight(1f),
            )
        }
    }
}

/** The sign-in code in large monospace, grouped 4+4; shared with the nearby-phone panel. */
internal val TvSignInCodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    lineHeight = 40.sp,
    letterSpacing = 2.sp,
)

/** A QR at least a third of the 540dp reference surface. */
private val MinQrSize: Dp = 180.dp

/**
 * Type and inset for the sign-in card's side-by-side secondary actions. Branch
 * -local on purpose: [TvAuthFormDefaults] is shared with server setup, sign-up
 * and first-run setup, and those screens have no reason to shrink.
 */
private val TvLoginSecondaryActionFontSize = 16.sp
private val TvLoginSecondaryActionPadding = 10.dp

private object TvLoginTextStyles {
    val Hero = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    )

    val Title = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    )

    val Body = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    val Status = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )

    val Field = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        color = Color.White,
    )

    /** Mono uppercase caption that labels each input — mirrors server setup. */
    val InputLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 19.sp,
        letterSpacing = 3.sp,
    )

    val Error = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
}

/** How long a finished sign-in waits for a nearby phone's session to wrap up before routing on. */
private const val NEARBY_RESULT_WAIT_MS = 5_000L
