package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.android.ui.components.marquee.MarqueeButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorHaptic
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorText
import org.siloserver.silo.android.ui.components.marquee.MarqueeFieldGroup
import org.siloserver.silo.android.ui.components.marquee.MarqueeHeadline
import org.siloserver.silo.android.ui.components.marquee.MarqueeIconButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeSeparator
import org.siloserver.silo.android.ui.components.marquee.MarqueeStage
import org.siloserver.silo.android.ui.components.marquee.MarqueeTextField
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle

/**
 * Account registration with an invite code, laid out like the other
 * first-run screens: Username, Email, Password and Invite code as one grouped
 * block and "Create account". Back returns to sign-in.
 */
@Composable
fun SignupScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    viewModel: SignupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.signupSuccess) {
        if (state.signupSuccess) {
            viewModel.onSignupSuccessConsumed()
            onNavigateToProfiles()
        }
    }
    MarqueeErrorHaptic(state.error)

    MarqueeStage(
        scrim = MarqueeScrimStyle.BottomDeep,
        frostStart = 0.40f,
        onBack = onNavigateToLogin,
        blockBack = state.isLoading,
        topBar = {
            MarqueeIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CommonR.string.account_backToSignIn), onNavigateToLogin, enabled = !state.isLoading)
        },
    ) {
        MarqueeHeadline(title = stringResource(CommonR.string.account_create_headline), lead = stringResource(CommonR.string.account_create_lead))
        MarqueeFieldGroup(isError = state.error != null, modifier = Modifier.padding(top = 24.dp)) {
            MarqueeTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChanged,
                icon = Icons.Outlined.Person,
                placeholder = stringResource(CommonR.string.tvSignIn_form_usernamePlaceholder),
            )
            MarqueeSeparator()
            MarqueeTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChanged,
                icon = Icons.Outlined.Email,
                placeholder = stringResource(CommonR.string.account_create_email),
                keyboardType = KeyboardType.Email,
            )
            MarqueeSeparator()
            MarqueeTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChanged,
                icon = Icons.Outlined.Lock,
                placeholder = stringResource(CommonR.string.tvSignIn_form_passwordPlaceholder),
                isSecure = true,
            )
            MarqueeSeparator()
            MarqueeTextField(
                value = state.inviteCode,
                onValueChange = viewModel::onInviteCodeChanged,
                icon = Icons.Outlined.ConfirmationNumber,
                placeholder = stringResource(CommonR.string.account_create_inviteCode),
                imeAction = ImeAction.Go,
                onImeAction = viewModel::onSignupClick,
            )
        }
        state.error?.let { MarqueeErrorText(it.text(), Modifier.padding(top = 10.dp)) }
        MarqueeButton(
            text = stringResource(if (state.isLoading) CommonR.string.account_create_creating else CommonR.string.account_create_submit),
            onClick = viewModel::onSignupClick,
            isLoading = state.isLoading,
            enabled = !state.isLoading,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}
