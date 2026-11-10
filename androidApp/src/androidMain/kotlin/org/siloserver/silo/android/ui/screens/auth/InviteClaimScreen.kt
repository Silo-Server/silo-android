package org.siloserver.silo.android.ui.screens.auth

import org.siloserver.silo.android.ui.components.SiloConfirmDialog
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
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
import org.siloserver.silo.common.i18n.text
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
        SiloConfirmDialog(
            title = stringResource(CommonR.string.setup_cleartext_title),
            body = stringResourceWithNames(CommonR.string.account_invite_cleartextBody, ServerBranding.hostLabel(origin)),
            confirmLabel = stringResource(CommonR.string.setup_cleartext_connect),
            destructive = false,
            onConfirm = viewModel::onConfirmCleartext,
            onDismiss = viewModel::onCancelCleartext,
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
                    title = stringResource(
                        if (state.signInRequiredUsername != null) CommonR.string.account_invite_createdTitle else CommonR.string.account_invite_checkTitle,
                    ),
                    lead = state.signInRequiredUsername?.let { username ->
                        stringResourceWithNames(CommonR.string.account_invite_createdLead, serverUrl, username)
                    } ?: state.error?.text().orEmpty(),
                )
                MarqueeButton(stringResource(CommonR.string.account_invite_goToSignIn), onClick = onNavigateToLogin, modifier = Modifier.padding(top = 24.dp))
            }

            state.acceptanceUnavailable || state.invitation?.acceptanceAvailable == false -> {
                MarqueeHeadline(
                    title = stringResource(CommonR.string.account_invite_unavailableTitle),
                    lead = stringResource(CommonR.string.account_invite_unavailableLead),
                )
                MarqueeButton(
                    stringResource(CommonR.string.account_backToSignIn),
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
                    title = stringResource(CommonR.string.account_invite_lookupFailedTitle),
                    lead = stringResource(CommonR.string.account_invite_lookupFailedLead),
                )
                MarqueeButton(stringResource(CommonR.string.common_tryAgain), onClick = viewModel::onRetryLookup, modifier = Modifier.padding(top = 24.dp))
                MarqueeButton(
                    stringResource(CommonR.string.account_backToSignIn),
                    onClick = onNavigateToLogin,
                    kind = MarqueeButtonKind.Plain,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            state.invitationInvalid -> {
                MarqueeHeadline(
                    title = stringResource(CommonR.string.account_invite_invalidTitle),
                    lead = stringResource(CommonR.string.account_invite_invalidLead),
                )
                MarqueeButton(
                    stringResource(CommonR.string.account_backToSignIn),
                    onClick = onNavigateToLogin,
                    kind = MarqueeButtonKind.Glass,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }

            else -> {
                val invitation = state.invitation ?: return@MarqueeStage
                MarqueeHeadline(
                    title = stringResourceWithNames(CommonR.string.account_invite_welcome, invitation.serverName),
                    lead = listOfNotNull(
                        invitation.inviterName?.let { stringResourceWithNames(CommonR.string.account_invite_invitedBy, it) },
                        stringResource(CommonR.string.account_invite_lead),
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
                        placeholder = stringResource(CommonR.string.tvSignIn_form_passwordPlaceholder),
                        isSecure = true,
                    )
                    MarqueeSeparator()
                    MarqueeTextField(
                        value = state.confirmPassword,
                        onValueChange = viewModel::onConfirmPasswordChanged,
                        icon = Icons.Outlined.Lock,
                        placeholder = stringResource(CommonR.string.account_invite_confirmPassword),
                        isSecure = true,
                        imeAction = ImeAction.Go,
                        onImeAction = viewModel::onClaimClick,
                    )
                }
                state.error?.let { MarqueeErrorText(it.text(), Modifier.padding(top = 10.dp)) }
                MarqueeButton(
                    text = stringResource(if (state.isSubmitting) CommonR.string.account_create_creating else CommonR.string.account_create_submit),
                    onClick = viewModel::onClaimClick,
                    isLoading = state.isSubmitting,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}
