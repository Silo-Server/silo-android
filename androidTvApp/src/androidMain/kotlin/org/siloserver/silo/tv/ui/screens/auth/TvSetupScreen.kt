package org.siloserver.silo.tv.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeBody
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButton
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeErrorText
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeField
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeHeadline
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeScreen
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeCard
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeCardSymbol
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.components.rememberTvImeAwareFormScrollState
import org.siloserver.silo.tv.ui.components.tvImeAwareFieldContext
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus

/**
 * First-admin account creation on a server that has none yet, laid out like
 * the other TV first-run screens: the form on the left, a card on the right.
 */
@Composable
fun TvSetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: TvSetupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val usernameFocus = remember { FocusRequester() }

    LaunchedEffect(state.setupSuccess) {
        if (state.setupSuccess) {
            viewModel.onSetupSuccessConsumed()
            onSetupComplete()
        }
    }
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
                TvMarqueeHeadline(stringResource(CommonR.string.setup_firstAdmin_headline))
                TvMarqueeBody(
                    stringResource(CommonR.string.setup_firstAdmin_lead),
                    modifier = Modifier.padding(top = 11.dp),
                )
                Column(
                    Modifier.width(380.dp).padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.tvImeAwareFieldContext()) {
                        TvMarqueeField(
                            value = state.username,
                            onValueChange = viewModel::onUsernameChanged,
                            icon = Icons.Outlined.Person,
                            placeholder = stringResource(CommonR.string.tvSignIn_form_usernamePlaceholder),
                            enabled = !state.isLoading,
                            focusRequester = usernameFocus,
                        )
                    }
                    Box(Modifier.tvImeAwareFieldContext()) {
                        TvMarqueeField(
                            value = state.email,
                            onValueChange = viewModel::onEmailChanged,
                            icon = Icons.Outlined.Email,
                            placeholder = stringResource(CommonR.string.account_create_email),
                            keyboardType = KeyboardType.Email,
                            enabled = !state.isLoading,
                        )
                    }
                    Box(Modifier.tvImeAwareFieldContext()) {
                        TvMarqueeField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChanged,
                            icon = Icons.Outlined.Lock,
                            placeholder = stringResource(CommonR.string.tvSignIn_form_passwordPlaceholder),
                            isSecure = true,
                            imeAction = ImeAction.Done,
                            onImeAction = { if (!state.isLoading) viewModel.onCreateAccountClick() },
                            isError = state.error != null,
                            enabled = !state.isLoading,
                        )
                    }
                }
                state.error?.let { TvMarqueeErrorText(it.text(), Modifier.width(380.dp).padding(top = 8.dp)) }
                TvMarqueeButton(
                    text = stringResource(if (state.isLoading) CommonR.string.tvSignIn_form_creatingAccount else CommonR.string.account_create_submit),
                    onClick = viewModel::onCreateAccountClick,
                    isLoading = state.isLoading,
                    enabled = !state.isLoading,
                    modifier = Modifier.padding(top = 20.dp),
                )
            },
            card = {
                TvMarqueeCard {
                    TvMarqueeCardSymbol(Icons.Outlined.AdminPanelSettings)
                    Text(
                        stringResource(CommonR.string.tvSignIn_setup_cardTitle),
                        color = MarqueeColors.Ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 17.dp),
                    )
                    TvMarqueeBody(
                        stringResource(CommonR.string.tvSignIn_setup_cardBody),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            },
        )
    }
}
