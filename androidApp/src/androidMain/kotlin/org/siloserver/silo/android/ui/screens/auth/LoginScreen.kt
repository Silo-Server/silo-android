package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import org.siloserver.silo.android.auth.openNativeSignIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.aurora.AuroraEyebrow
import org.siloserver.silo.android.ui.components.aurora.AuroraErrorLabel
import org.siloserver.silo.android.ui.components.aurora.AuroraGhostButton
import org.siloserver.silo.android.ui.components.aurora.AuroraInkTertiary
import org.siloserver.silo.android.ui.components.aurora.AuroraPrimaryButton
import org.siloserver.silo.android.ui.components.aurora.AuroraScreen
import org.siloserver.silo.android.ui.components.aurora.AuroraScrim
import org.siloserver.silo.android.ui.components.aurora.AuroraTextField
import org.siloserver.silo.android.ui.components.aurora.AuroraVariant
import org.siloserver.silo.android.ui.components.aurora.auroraGlass
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sign-in. Mirrors silo-apple iOS phone `LoginView` (Aurora): wordmark,
 * "Step 02 — Sign in" eyebrow + "Welcome back", then a glass card with
 * "Continue as <owner>" first when the server lists a network provider for
 * this device (such as Tailscale: no browser, no password), a
 * "Sign in with <provider>" button per external provider the server lists
 * (OIDC, run in a Custom Tab), "Use a different account" when the server
 * takes `prompt=select_account`, Username + Password fields and the cream
 * "Sign in" button when any listed provider takes a password (the local one
 * or a directory such as LDAP), and the Create-account / Change-server ghost
 * buttons.
 *
 * @param signupEnabled Whether the "Create account" ghost is shown.
 * @param onNavigateToSignup Tapped "Create account".
 * @param onNavigateToProfiles Called after a successful login.
 * @param onChangeServer Tapped "Change server" (back to server setup).
 */
@Composable
fun LoginScreen(
    signupEnabled: Boolean = false,
    onNavigateToSignup: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onChangeServer: () -> Unit = {},
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showPassword by remember { mutableStateOf(false) }

    // Coming from server setup the next action is always typing a username;
    // focus it immediately so the keyboard stays up across the transition.
    val usernameFocus = remember { FocusRequester() }
    LaunchedEffect(state.showPasswordForm) {
        if (state.showPasswordForm) runCatching { usernameFocus.requestFocus() }
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
            onNavigateToProfiles()
        }
    }

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

    AuroraScreen(variant = AuroraVariant.SignIn, scrim = AuroraScrim.Soft) {
        SiloLogo()

        Spacer(Modifier.height(30.dp))
        AuroraEyebrow(text = "Step 02 — Sign in", centered = true)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Welcome back",
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFF3EFE9),
        )
        state.serverHostLabel?.let { host ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = host,
                modifier = Modifier.fillMaxWidth(),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = AuroraInkTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(24.dp))

        Column2Glass {
            // No password form until discovery answers: a server that signs in
            // only through a provider must not flash one (silo-apple parity).
            if (state.options == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).then(announced),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = AuroraInkTertiary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(text = "Loading sign-in options…", color = AuroraInkTertiary, fontSize = 14.sp)
                }
            }
            state.networkProvider?.let { provider ->
                SignInProviderButton(
                    provider = provider,
                    label = continueAsLabel(provider),
                    supportingText = continueViaLabel(provider),
                    onClick = viewModel::onNetworkSignIn,
                    busy = state.networkSignInBusy,
                    enabled = !state.signInBusy,
                )
            }
            state.providers.forEach { provider ->
                SignInProviderButton(
                    provider = provider,
                    label = signInWithLabel(provider),
                    onClick = { viewModel.onProviderClick(provider) },
                    busy = state.providerBusy == provider.id,
                    enabled = !state.signInBusy,
                )
            }
            if (state.offersAccountChoice) {
                AuroraGhostButton(
                    label = differentAccountLabel(state.providers),
                    onClick = viewModel::onUseDifferentAccount,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            // Discovery failed: the password form still shows, and this row
            // says the providers may be missing and retries.
            if (state.optionsUnavailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Couldn't load sign-in options.",
                        modifier = Modifier.weight(1f).then(announced),
                        color = AuroraInkTertiary,
                        fontSize = 13.sp,
                    )
                    AuroraGhostButton(label = "Retry", onClick = viewModel::loadOptions)
                }
            }
            if ((state.providers.isNotEmpty() || state.networkProvider != null) && state.showPasswordForm) {
                OrDivider()
            }
            if (!state.showPasswordForm) {
                if (state.options != null && state.providers.isEmpty() && state.networkProvider == null) {
                    // Password sign-in is off and the provider can't run in this app.
                    AuroraErrorLabel("This server doesn't offer a sign-in this app can use. Sign in on the web, or ask the server's admin.")
                }
                state.error?.let { AuroraErrorLabel(it, modifier = announced) }
                if (state.isLoading) {
                    Text(
                        text = "Finishing sign-in…",
                        modifier = Modifier.fillMaxWidth().then(announced),
                        color = AuroraInkTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (state.showPasswordForm) {
                AuroraTextField(
                    label = "Username",
                    value = state.username,
                    onValueChange = viewModel::onUsernameChanged,
                    modifier = Modifier.focusRequester(usernameFocus),
                    placeholder = "yourname",
                    imeAction = ImeAction.Next,
                    enabled = !state.signInBusy,
                )
                AuroraTextField(
                    label = "Password",
                    value = state.password,
                    onValueChange = viewModel::onPasswordChanged,
                    placeholder = "••••••",
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                    onImeAction = viewModel::onLoginClick,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    enabled = !state.signInBusy,
                    trailing = {
                        IconButton(onClick = { showPassword = !showPassword }, enabled = !state.signInBusy) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = Color.White.copy(alpha = 0.62f),
                            )
                        }
                    },
                )

                state.error?.let { AuroraErrorLabel(it, modifier = announced) }

                AuroraPrimaryButton(
                    label = if (state.isLoading) "Signing in…" else "Sign in",
                    onClick = viewModel::onLoginClick,
                    isLoading = state.isLoading,
                    // Any sign-in under way: the view model takes no password until it ends.
                    enabled = !state.signInBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp, androidx.compose.ui.Alignment.CenterHorizontally),
            ) {
                if (state.signupEnabled) {
                    AuroraGhostButton(label = "Create account", onClick = onNavigateToSignup)
                }
                AuroraGhostButton(label = "Change server", onClick = onChangeServer)
            }
        }
    }
}

/** "or" between the provider buttons and the password form. */
/** "Use a different account" with several providers: which one to sign in with. */
@Composable
private fun AccountProviderChooser(
    providers: List<org.siloserver.silo.model.auth.SignInProvider>,
    onChoose: (org.siloserver.silo.model.auth.SignInProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use a different account") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                providers.forEach { provider ->
                    androidx.compose.material3.TextButton(onClick = { onChoose(provider) }, modifier = Modifier.fillMaxWidth()) {
                        Text(signInWithLabel(provider))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        Text(
            text = "or",
            modifier = Modifier.padding(horizontal = 12.dp),
            color = AuroraInkTertiary,
            fontSize = 13.sp,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
    }
}

@Composable
private fun Column2Glass(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .auroraGlass(cornerRadius = 24.dp, emphasized = true)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}
