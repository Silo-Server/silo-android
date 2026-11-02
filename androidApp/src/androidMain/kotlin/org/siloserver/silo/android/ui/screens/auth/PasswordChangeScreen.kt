package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.marquee.MarqueeButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeButtonKind
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorHaptic
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorText
import org.siloserver.silo.android.ui.components.marquee.MarqueeFieldGroup
import org.siloserver.silo.android.ui.components.marquee.MarqueeHeadline
import org.siloserver.silo.android.ui.components.marquee.MarqueeSeparator
import org.siloserver.silo.android.ui.components.marquee.MarqueeStage
import org.siloserver.silo.android.ui.components.marquee.MarqueeTextField
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.marqueeShake
import org.siloserver.silo.viewmodel.PasswordChangePhase
import org.siloserver.silo.viewmodel.PasswordChangeViewModel

/**
 * "Choose a new password", shown after signing in with a temporary password
 * an admin set (silo-apple `PasswordChangeView`). [PasswordChangeViewModel]
 * owns the rules; this screen lays them out.
 *
 * @param onFinished The session no longer carries the restriction; continue to profile selection.
 * @param onSignOut "Sign out".
 */
@Composable
fun PasswordChangeScreen(
    onFinished: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: PasswordChangeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        MarqueeScene.focus = MarqueeScene.Focus.Account
        MarqueeScene.personalTint = null
    }
    LaunchedEffect(state.finished) {
        if (state.finished) {
            viewModel.onFinishedConsumed()
            onFinished()
        }
    }
    MarqueeErrorHaptic(state.error)

    val save = {
        focusManager.clearFocus()
        viewModel.save()
    }
    val announced = Modifier.semantics { liveRegion = LiveRegionMode.Polite }

    MarqueeStage(scrim = MarqueeScrimStyle.BottomDeep, frostStart = 0.40f) {
        when (val phase = state.phase) {
            is PasswordChangePhase.RefreshFailed -> Outcome(
                title = "Password changed",
                lead = "Your new password is saved, but signing you in didn't finish.",
                action = if (phase.retrying) "Trying again…" else "Try again",
                busy = phase.retrying,
                onAction = viewModel::retryRefresh,
                onSignOut = onSignOut,
            )
            is PasswordChangePhase.Unconfirmed -> Outcome(
                title = "Password not confirmed",
                lead = "Silo couldn't check whether your new password was saved. Try again in a moment.",
                action = if (phase.checking) "Checking…" else "Try again",
                busy = phase.checking,
                onAction = viewModel::confirmChange,
                onSignOut = onSignOut,
            )
            PasswordChangePhase.Editing, PasswordChangePhase.Saving -> {
                MarqueeHeadline(title = "Choose a new password", lead = state.lead)
                Column(Modifier.fillMaxWidth().padding(top = 24.dp).animateContentSize()) {
                    MarqueeFieldGroup(isError = state.error != null, modifier = Modifier.marqueeShake(state.errorCount)) {
                        MarqueeTextField(
                            value = state.currentPassword,
                            onValueChange = viewModel::onCurrentPasswordChanged,
                            icon = Icons.Outlined.Key,
                            placeholder = "Temporary password",
                            keyboardType = KeyboardType.Password,
                            isSecure = true,
                            enabled = !state.isBusy,
                        )
                        MarqueeSeparator()
                        MarqueeTextField(
                            value = state.newPassword,
                            onValueChange = viewModel::onNewPasswordChanged,
                            icon = Icons.Outlined.Lock,
                            placeholder = "New password",
                            keyboardType = KeyboardType.Password,
                            isSecure = true,
                            enabled = !state.isBusy,
                        )
                        MarqueeSeparator()
                        MarqueeTextField(
                            value = state.confirmation,
                            onValueChange = viewModel::onConfirmationChanged,
                            icon = Icons.Outlined.Lock,
                            placeholder = "Confirm new password",
                            keyboardType = KeyboardType.Password,
                            isSecure = true,
                            enabled = !state.isBusy,
                            imeAction = ImeAction.Go,
                            onImeAction = save,
                        )
                    }
                    Text(
                        state.requirementsHint,
                        color = MarqueeColors.InkTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                    )
                    state.error?.let { MarqueeErrorText(it, Modifier.padding(top = 10.dp, start = 4.dp).then(announced)) }
                    MarqueeButton(
                        text = if (state.isBusy) "Saving…" else "Save password",
                        onClick = save,
                        isLoading = state.isBusy,
                        enabled = !state.isBusy,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    SignOutButton(enabled = !state.isBusy, onSignOut = onSignOut)
                }
            }
        }
    }
}

/** The password may have changed, so the form stays closed: one action, or sign out. */
@Composable
private fun Outcome(
    title: String,
    lead: String,
    action: String,
    busy: Boolean,
    onAction: () -> Unit,
    onSignOut: () -> Unit,
) {
    MarqueeHeadline(title = title, lead = lead)
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        MarqueeButton(text = action, onClick = onAction, isLoading = busy, enabled = !busy)
        SignOutButton(enabled = !busy, onSignOut = onSignOut)
    }
}

@Composable
private fun SignOutButton(enabled: Boolean, onSignOut: () -> Unit) {
    MarqueeButton(
        text = "Sign out",
        onClick = onSignOut,
        kind = MarqueeButtonKind.Plain,
        enabled = enabled,
        modifier = Modifier.padding(top = 6.dp),
    )
}
