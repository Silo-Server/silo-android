package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import org.siloserver.silo.android.ui.components.marquee.MarqueeButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorHaptic
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorText
import org.siloserver.silo.android.ui.components.marquee.MarqueeFieldGroup
import org.siloserver.silo.android.ui.components.marquee.MarqueeHeadline
import org.siloserver.silo.android.ui.components.marquee.MarqueeSeparator
import org.siloserver.silo.android.ui.components.marquee.MarqueeStage
import org.siloserver.silo.android.ui.components.marquee.MarqueeTextField
import org.siloserver.silo.android.ui.components.marquee.MarqueeWordmark
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle

/**
 * First-run admin account creation on a server that has none yet. An Android
 * capability (the Apple apps send the person to the browser for this), laid
 * out like the other first-run screens: Username, Email and Password as one
 * grouped block and "Create account".
 */
@Composable
fun SetupScreen(
    onNavigateToProfiles: () -> Unit,
    viewModel: SetupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.setupSuccess) {
        if (state.setupSuccess) {
            viewModel.onSetupSuccessConsumed()
            onNavigateToProfiles()
        }
    }
    MarqueeErrorHaptic(state.error)

    MarqueeStage(
        scrim = MarqueeScrimStyle.BottomDeep,
        frostStart = 0.40f,
        topBar = { MarqueeWordmark() },
    ) {
        MarqueeHeadline(
            title = "Set up\nyour server",
            lead = "This server has no accounts yet. Create the first one; it becomes the admin.",
        )
        MarqueeFieldGroup(isError = state.error != null, modifier = Modifier.padding(top = 24.dp)) {
            MarqueeTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChanged,
                icon = Icons.Outlined.Person,
                placeholder = "Username",
            )
            MarqueeSeparator()
            MarqueeTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChanged,
                icon = Icons.Outlined.Email,
                placeholder = "Email",
                keyboardType = KeyboardType.Email,
            )
            MarqueeSeparator()
            MarqueeTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChanged,
                icon = Icons.Outlined.Lock,
                placeholder = "Password",
                isSecure = true,
                imeAction = ImeAction.Go,
                onImeAction = viewModel::onCreateAccountClick,
            )
        }
        state.error?.let { MarqueeErrorText(it, Modifier.padding(top = 10.dp)) }
        MarqueeButton(
            text = if (state.isLoading) "Creating…" else "Create account",
            onClick = viewModel::onCreateAccountClick,
            isLoading = state.isLoading,
            enabled = !state.isLoading,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}
