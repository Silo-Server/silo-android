package org.siloserver.silo.tv.ui.screens.auth

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeBody
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButton
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButtonKind
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeErrorText
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeField
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeHeadline
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeScreen
import org.siloserver.silo.tv.ui.components.marquee.TvUsePhoneCard
import org.siloserver.silo.tv.ui.components.rememberTvImeAwareFormScrollState
import org.siloserver.silo.tv.ui.components.tvImeAwareFieldContext
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus

/**
 * Account registration with an invite code, laid out like the other TV
 * first-run screens. Back returns to sign-in.
 */
@Composable
fun TvSignupScreen(
    onSignupComplete: () -> Unit,
    onBackToLogin: () -> Unit,
    viewModel: TvSignupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val usernameFocus = remember { FocusRequester() }

    LaunchedEffect(state.signupSuccess) {
        if (state.signupSuccess) {
            viewModel.onSignupSuccessConsumed()
            onSignupComplete()
        }
    }
    BackHandler(enabled = state.isLoading) {}
    // A text field on a first-run screen: if this claim is dropped the
    // remote has nothing to act on and no touch fallback exists. Null
    // contentKey in touch mode (a programmatic claim on a text field pops the
    // IME); flipping back to key input re-runs the claim.
    val inputMode = LocalInputModeManager.current.inputMode
    val usernameFocusModifier = rememberTvContentInitialFocus(
        target = usernameFocus,
        contentKey = if (inputMode == InputMode.Touch) null else inputMode,
    )

    Box(Modifier.fillMaxSize().then(usernameFocusModifier).imePadding()) {
        TvMarqueeScreen(
            copyScroll = rememberTvImeAwareFormScrollState(),
            copy = {
                TvMarqueeHeadline("Create your\naccount")
                TvMarqueeBody("Join this Silo server with your invite.", modifier = Modifier.padding(top = 11.dp))
                Column(
                    Modifier.width(380.dp).padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.tvImeAwareFieldContext()) {
                        TvMarqueeField(
                            value = state.username,
                            onValueChange = viewModel::onUsernameChanged,
                            icon = Icons.Outlined.Person,
                            placeholder = "Username",
                            enabled = !state.isLoading,
                            focusRequester = usernameFocus,
                        )
                    }
                    Box(Modifier.tvImeAwareFieldContext()) {
                        TvMarqueeField(
                            value = state.email,
                            onValueChange = viewModel::onEmailChanged,
                            icon = Icons.Outlined.Email,
                            placeholder = "Email",
                            keyboardType = KeyboardType.Email,
                            enabled = !state.isLoading,
                        )
                    }
                    Box(Modifier.tvImeAwareFieldContext()) {
                        TvMarqueeField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChanged,
                            icon = Icons.Outlined.Lock,
                            placeholder = "Password",
                            isSecure = true,
                            enabled = !state.isLoading,
                        )
                    }
                    Box(Modifier.tvImeAwareFieldContext()) {
                        TvMarqueeField(
                            value = state.inviteCode,
                            onValueChange = viewModel::onInviteCodeChanged,
                            icon = Icons.Outlined.ConfirmationNumber,
                            placeholder = "Invite code",
                            imeAction = ImeAction.Done,
                            onImeAction = { if (!state.isLoading) viewModel.onSignupClick() },
                            isError = state.error != null,
                            enabled = !state.isLoading,
                        )
                    }
                }
                state.error?.let { TvMarqueeErrorText(it, Modifier.width(380.dp).padding(top = 8.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    TvMarqueeButton(
                        text = if (state.isLoading) "Creating account…" else "Create account",
                        onClick = viewModel::onSignupClick,
                        isLoading = state.isLoading,
                        enabled = !state.isLoading,
                    )
                    TvMarqueeButton(
                        text = "Sign in instead",
                        onClick = onBackToLogin,
                        kind = TvMarqueeButtonKind.Plain,
                        enabled = !state.isLoading,
                    )
                }
            },
            card = {
                TvUsePhoneCard("Already have an account? Open Silo on a phone or tablet on the same Wi‑Fi and it can sign this TV in for you.")
            },
        )
    }
}
