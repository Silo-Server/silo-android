package org.siloserver.silo.android.ui.screens.auth

import org.siloserver.silo.android.ui.components.SiloDialogAction
import org.siloserver.silo.android.ui.components.SiloDialog
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.auth.openNativeSignIn
import org.siloserver.silo.android.ui.components.marquee.MarqueeButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeButtonKind
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorHaptic
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorText
import org.siloserver.silo.android.ui.components.marquee.MarqueeFieldGroup
import org.siloserver.silo.android.ui.components.marquee.MarqueeHeadline
import org.siloserver.silo.android.ui.components.marquee.MarqueeIconButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeLabeledDivider
import org.siloserver.silo.android.ui.components.marquee.MarqueeMetrics
import org.siloserver.silo.android.ui.components.marquee.MarqueeProviderMark
import org.siloserver.silo.android.ui.components.marquee.MarqueeSeparator
import org.siloserver.silo.android.ui.components.marquee.MarqueeServerChip
import org.siloserver.silo.android.ui.components.marquee.MarqueeStage
import org.siloserver.silo.android.ui.components.marquee.MarqueeTextField
import org.siloserver.silo.android.ui.components.marquee.MarqueeTopBarSpacer
import org.siloserver.silo.common.ui.marquee.marqueeShake
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.ServerBrandingLoader
import org.siloserver.silo.common.ui.marquee.SignInHandoff
import org.siloserver.silo.common.ui.marquee.rememberReduceMotion
import org.siloserver.silo.common.ui.marquee.rememberSignInServer
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.network.ServerRegistry

/**
 * Sign-in, laid out like silo-apple's phone `LoginView` (Marquee): "Sign in"
 * with the server's branded subtitle, then "Continue as <owner>" first when
 * the server lists a network provider for this device (such as Tailscale),
 * a "Sign in with <provider>" button per external provider (OIDC, run in a
 * Custom Tab), "Use a different account" when the server takes
 * `prompt=select_account`, and the username and password form when any
 * listed provider takes a password. With two or more providers the password
 * form waits behind "Sign in with a password". [LoginViewModel] owns the
 * sign-in rules; this screen lays them out.
 *
 * @param signupEnabled Whether "Create account" is shown.
 * @param onNavigateToSignup Tapped "Create account".
 * @param onNavigateToProfiles Called after a successful sign-in.
 * @param onChangeServer Back, or "Use a different server" from the server chip.
 */
@Composable
fun LoginScreen(
    signupEnabled: Boolean = false,
    onNavigateToSignup: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onChangeServer: () -> Unit = {},
    viewModel: LoginViewModel = koinViewModel(),
    brandingLoader: ServerBrandingLoader = koinInject(),
    serverRegistry: ServerRegistry = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val activeEntry by serverRegistry.activeEntry.collectAsState()
    val server = rememberSignInServer(
        serverUrl = activeEntry?.url.orEmpty(),
        savedName = activeEntry?.fetchedName,
        loader = brandingLoader,
    )
    val focusManager = LocalFocusManager.current
    val usernameFocus = remember { FocusRequester() }
    var expandsPasswordForm by remember { mutableStateOf(false) }
    var passwordFailures by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        MarqueeScene.focus = MarqueeScene.Focus.Account
        MarqueeScene.personalTint = null
    }

    // A provider's native start opens in a Custom Tab; the app redirect
    // brings the result back through NativeSignInCoordinator.
    val context = LocalContext.current
    LaunchedEffect(state.browserLaunch) {
        val url = state.browserLaunch ?: return@LaunchedEffect
        viewModel.onBrowserLaunchHandled(context.openNativeSignIn(url))
    }

    LaunchedEffect(signupEnabled) { viewModel.setSignupEnabled(signupEnabled) }
    LaunchedEffect(state.loginSuccess) {
        if (state.loginSuccess) {
            viewModel.onLoginSuccessConsumed()
            SignInHandoff.skipsSingleProfilePicker = true
            onNavigateToProfiles()
        }
    }
    // Each rejected password shakes the fields; an error already showing
    // when the screen comes back doesn't.
    val initialError = remember { state.error }
    LaunchedEffect(state.error) {
        if (state.error != null && state.error != initialError && state.showPasswordForm && state.password.isNotEmpty()) {
            passwordFailures++
        }
    }
    MarqueeErrorHaptic(state.error)

    // Browser sign-in results arrive after the Custom Tab closes, away from
    // where focus is: announce them.
    val announced = Modifier.semantics { liveRegion = LiveRegionMode.Polite }

    if (state.choosingAccountProvider) {
        AccountProviderChooser(
            providers = state.providers,
            onChoose = viewModel::onAccountProviderChosen,
            onDismiss = viewModel::onAccountChoiceDismissed,
        )
    }

    // With two or more providers the password form waits behind a button,
    // so the providers stay the first thing on screen.
    val foldsPasswordForm = state.providers.size >= 2 && !expandsPasswordForm
    // Coming from server setup the next action is typing a username; focus
    // it as soon as the form shows so the keyboard stays up across the
    // transition. A folded form waits until it's opened.
    LaunchedEffect(state.showPasswordForm, foldsPasswordForm) {
        if (state.showPasswordForm && !foldsPasswordForm) runCatching { usernameFocus.requestFocus() }
    }

    // Leaving mid-sign-in would let the finished sign-in pull the app back
    // from server setup, so going back waits until it settles.
    MarqueeStage(
        scrim = MarqueeScrimStyle.BottomDeep,
        frostStart = 0.40f,
        onBack = onChangeServer,
        blockBack = state.signInBusy,
        topBar = {
            MarqueeIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Change server",
                onClick = onChangeServer,
                enabled = !state.signInBusy,
            )
            MarqueeTopBarSpacer()
            MarqueeServerChip(
                name = server.name,
                hostLabel = server.hostLabel,
                markUrl = server.branding?.markUrl,
                actionLabel = "Use a different server",
                onAction = onChangeServer,
                enabled = !state.signInBusy,
            )
        },
    ) {
        MarqueeHeadline(
            title = "Sign in",
            lead = server.branding?.loginSubtitle ?: "Use your ${server.name} account.",
        )

        Column(Modifier.fillMaxWidth().padding(top = 24.dp).animateContentSize()) {
            if (state.options == null) {
                LoadingSkeleton()
                return@Column
            }

            state.networkProvider?.let { provider ->
                MarqueeButton(
                    onClick = viewModel::onNetworkSignIn,
                    isLoading = state.networkSignInBusy,
                    enabled = !state.signInBusy,
                    modifier = Modifier.semantics {
                        contentDescription = if (state.networkSignInBusy) "Signing in…" else continueAsLabel(provider)
                    },
                ) {
                    if (state.networkSignInBusy) {
                        Text("Signing in…")
                    } else {
                        NetworkSignInLabel(provider)
                    }
                }
                if (state.providers.isNotEmpty() || state.showPasswordForm) {
                    MarqueeLabeledDivider("or", Modifier.padding(vertical = 18.dp))
                }
            }

            state.providers.forEachIndexed { index, provider ->
                val busy = state.providerBusy == provider.id
                // The first provider leads unless a network sign-in already does.
                val primary = index == 0 && state.networkProvider == null
                MarqueeButton(
                    onClick = { viewModel.onProviderClick(provider) },
                    kind = if (primary) MarqueeButtonKind.Primary else MarqueeButtonKind.Glass,
                    isLoading = busy,
                    enabled = !state.signInBusy,
                    modifier = Modifier.padding(top = if (index > 0) 10.dp else 0.dp),
                ) {
                    if (busy) {
                        Text("Waiting for sign-in…")
                    } else {
                        MarqueeProviderMark(provider)
                        Text(signInWithLabel(provider), maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
            if (state.offersAccountChoice) {
                MarqueeButton(
                    text = differentAccountLabel(state.providers),
                    onClick = viewModel::onUseDifferentAccount,
                    kind = MarqueeButtonKind.Plain,
                    enabled = !state.signInBusy,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (state.options != null && !state.showPasswordForm && state.providers.isEmpty() && state.networkProvider == null) {
                MarqueeErrorText(
                    "This server doesn't offer a sign-in this app can use. Sign in on the web, or ask the server's admin.",
                )
            }

            // Discovery failed: the password form still shows, and this row
            // says the providers may be missing and retries.
            if (state.optionsUnavailable) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MarqueeErrorText("Couldn't load sign-in options.", Modifier.weight(1f).then(announced))
                    TextButton(onClick = viewModel::loadOptions, enabled = !state.signInBusy) {
                        Text("Retry", color = MarqueeColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (state.showPasswordForm) {
                if (state.providers.isNotEmpty()) {
                    MarqueeLabeledDivider(
                        if (foldsPasswordForm) "or" else "or use your password",
                        Modifier.padding(vertical = 18.dp),
                    )
                }
                if (foldsPasswordForm) {
                    MarqueeButton(
                        text = "Sign in with a password",
                        onClick = { expandsPasswordForm = true },
                        kind = MarqueeButtonKind.Plain,
                        icon = Icons.Outlined.Key,
                        enabled = !state.signInBusy,
                    )
                    // A provider's failure has no form to show it in.
                    state.error?.let { MarqueeErrorText(it, Modifier.padding(top = 14.dp).then(announced)) }
                } else {
                    MarqueeFieldGroup(isError = state.error != null, modifier = Modifier.marqueeShake(passwordFailures)) {
                        MarqueeTextField(
                            value = state.username,
                            onValueChange = viewModel::onUsernameChanged,
                            icon = Icons.Outlined.Person,
                            placeholder = "Username",
                            imeAction = ImeAction.Next,
                            focusRequester = usernameFocus,
                            enabled = !state.signInBusy,
                        )
                        MarqueeSeparator()
                        MarqueeTextField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChanged,
                            icon = Icons.Outlined.Lock,
                            placeholder = "Password",
                            isSecure = true,
                            isError = state.error != null,
                            enabled = !state.signInBusy,
                            imeAction = ImeAction.Go,
                            onImeAction = {
                                focusManager.clearFocus()
                                viewModel.onLoginClick()
                            },
                        )
                    }
                    state.error?.let { MarqueeErrorText(it, Modifier.padding(top = 10.dp, start = 4.dp).then(announced)) }
                    MarqueeButton(
                        text = if (state.isLoading) "Signing in…" else "Sign in",
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.onLoginClick()
                        },
                        isLoading = state.isLoading,
                        // Any sign-in under way: the view model takes no password until it ends.
                        enabled = !state.signInBusy,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            } else {
                state.error?.let { MarqueeErrorText(it, Modifier.padding(top = 14.dp).then(announced)) }
                if (state.isLoading) {
                    Text(
                        text = "Finishing sign-in…",
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).then(announced),
                        color = MarqueeColors.InkTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Footnote(state)

            if (state.signupEnabled) {
                MarqueeButton(
                    text = "Create account",
                    onClick = onNavigateToSignup,
                    kind = MarqueeButtonKind.Plain,
                    enabled = !state.signInBusy,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NetworkSignInLabel(provider: SignInProvider) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MarqueeProviderMark(provider)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(continueAsLabel(provider), maxLines = 1)
            continueViaLabel(provider)?.let { via ->
                Text(via, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.alpha(0.7f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Footnote(state: LoginUiState) {
    val options = state.options ?: return
    val text = when {
        options.oauthProviders.isEmpty() && options.showPasswordForm -> "Forgot your password? Ask the server admin."
        options.oauthProviders.isNotEmpty() && !options.showPasswordForm -> "Password sign-in is turned off on this server."
        else -> return
    }
    Text(
        text,
        color = MarqueeColors.InkTertiary,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
}

/** Placeholders with a soft shimmer while the server's sign-in options load. */
@Composable
private fun LoadingSkeleton() {
    val reduceMotion = rememberReduceMotion()
    val shimmer = if (reduceMotion) {
        0.08f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(
            initialValue = 0.06f,
            targetValue = 0.12f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "skeletonAlpha",
        ).value
    }
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Loading sign-in options" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(MarqueeMetrics.ButtonHeight).clip(CircleShape).background(Color.White.copy(alpha = shimmer)))
        Box(Modifier.width(140.dp).height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = shimmer)))
        Box(
            Modifier
                .fillMaxWidth()
                .height(MarqueeMetrics.FieldHeight * 2)
                .clip(RoundedCornerShape(MarqueeMetrics.FieldCorner))
                .background(Color.White.copy(alpha = shimmer)),
        )
    }
}

/** "Use a different account" with several providers: which one to sign in with. */
@Composable
private fun AccountProviderChooser(
    providers: List<SignInProvider>,
    onChoose: (SignInProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    SiloDialog(
        title = "Use a different account",
        onDismissRequest = onDismiss,
        actions = providers.map { provider ->
            SiloDialogAction(label = signInWithLabel(provider), onClick = { onChoose(provider) })
        } + SiloDialogAction(label = "Cancel", onClick = onDismiss),
    )
}
