package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.marquee.MarqueeButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeButtonKind
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorHaptic
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorText
import org.siloserver.silo.android.ui.components.marquee.MarqueeFieldGroup
import org.siloserver.silo.android.ui.components.marquee.MarqueeHeadline
import org.siloserver.silo.android.ui.components.marquee.MarqueeMetrics
import org.siloserver.silo.android.ui.components.marquee.MarqueeSeparator
import org.siloserver.silo.android.ui.components.marquee.MarqueeStage
import org.siloserver.silo.android.ui.components.marquee.MarqueeTextField
import org.siloserver.silo.android.ui.components.marquee.MarqueeWordmark
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.ServerBranding

/**
 * Emailed-invitation claim: the deep link carried the server and token, so
 * this screen asks for a password and nothing else. Laid out like the other
 * first-run screens.
 */
@Composable
fun InviteClaimScreen(
    serverUrl: String,
    token: String,
    onNavigateToLogin: () -> Unit,
    onClaimComplete: () -> Unit,
    viewModel: InviteClaimViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(serverUrl, token) {
        viewModel.load(serverUrl, token)
    }

    // One-way latch: the success navigation clears the whole back stack, so
    // there is nothing to consume or reset.
    LaunchedEffect(state.claimSuccess) {
        if (state.claimSuccess) onClaimComplete()
    }
    MarqueeErrorHaptic(state.error)

    state.pendingCleartextOrigin?.let { origin ->
        AlertDialog(
            onDismissRequest = viewModel::onCancelCleartext,
            title = { Text("Connect without encryption?") },
            text = {
                Text(
                    "Your new password will be sent unencrypted to ${ServerBranding.hostLabel(origin)}. " +
                        "Only do this on a network you trust.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::onConfirmCleartext) { Text("Connect") } },
            dismissButton = { TextButton(onClick = viewModel::onCancelCleartext) { Text("Cancel") } },
        )
    }

    MarqueeStage(
        scrim = MarqueeScrimStyle.BottomDeep,
        frostStart = 0.40f,
        topBar = { MarqueeWordmark() },
    ) {
        when {
            state.signInRequiredUsername != null || state.acceptanceUncertain -> {
                MarqueeHeadline(
                    title = if (state.signInRequiredUsername != null) "Account created" else "Check sign-in",
                    lead = if (state.signInRequiredUsername != null) {
                        "Sign in to $serverUrl as ${state.signInRequiredUsername} using the password you chose. " +
                            "Use Change server on the sign-in screen if needed."
                    } else {
                        state.error.orEmpty()
                    },
                )
                MarqueeButton("Go to sign in", onClick = onNavigateToLogin, modifier = Modifier.padding(top = 24.dp))
            }

            state.acceptanceUnavailable || state.invitation?.acceptanceAvailable == false -> {
                MarqueeHeadline(title = "Can't accept here", lead = "Invitation acceptance is unavailable on this server.")
                MarqueeButton(
                    "Back to sign in",
                    onClick = onNavigateToLogin,
                    kind = MarqueeButtonKind.Glass,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }

            state.isLoadingInvitation -> {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MarqueeColors.Ink, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }

            state.lookupFailed -> {
                MarqueeHeadline(
                    title = "Couldn't check\nthe invitation",
                    lead = "Your invite is probably still fine — we just couldn't check it. " +
                        "Make sure you're online and try again.",
                )
                MarqueeButton("Try again", onClick = viewModel::onRetryLookup, modifier = Modifier.padding(top = 24.dp))
                MarqueeButton(
                    "Back to sign in",
                    onClick = onNavigateToLogin,
                    kind = MarqueeButtonKind.Plain,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            state.invitationInvalid -> {
                MarqueeHeadline(
                    title = "This invite\nhas expired",
                    lead = "The link may have been used already, revoked, or simply expired. " +
                        "Ask whoever invited you to send a fresh one.",
                )
                MarqueeButton(
                    "Back to sign in",
                    onClick = onNavigateToLogin,
                    kind = MarqueeButtonKind.Glass,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }

            else -> {
                val invitation = state.invitation ?: return@MarqueeStage
                MarqueeHeadline(
                    title = "Welcome to\n${invitation.serverName}",
                    lead = listOfNotNull(
                        invitation.inviterName?.let { "$it invited you." },
                        "Choose a password and you're in. You'll sign in with your email address.",
                    ).joinToString(" "),
                )
                MarqueeFieldGroup(isError = state.error != null, modifier = Modifier.padding(top = 24.dp)) {
                    // The address is fixed by the invitation; show it, don't edit it.
                    Row(
                        Modifier.fillMaxWidth().height(MarqueeMetrics.FieldHeight).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Email, contentDescription = null, tint = MarqueeColors.InkTertiary, modifier = Modifier.size(20.dp))
                        Text(
                            invitation.email,
                            color = MarqueeColors.InkSecondary,
                            fontSize = MarqueeMetrics.FieldFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MarqueeSeparator()
                    MarqueeTextField(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChanged,
                        icon = Icons.Outlined.Lock,
                        placeholder = "Password",
                        isSecure = true,
                    )
                    MarqueeSeparator()
                    MarqueeTextField(
                        value = state.confirmPassword,
                        onValueChange = viewModel::onConfirmPasswordChanged,
                        icon = Icons.Outlined.Lock,
                        placeholder = "Confirm password",
                        isSecure = true,
                        imeAction = ImeAction.Go,
                        onImeAction = viewModel::onClaimClick,
                    )
                }
                state.error?.let { MarqueeErrorText(it, Modifier.padding(top = 10.dp)) }
                MarqueeButton(
                    text = if (state.isSubmitting) "Creating…" else "Create account",
                    onClick = viewModel::onClaimClick,
                    isLoading = state.isSubmitting,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}
