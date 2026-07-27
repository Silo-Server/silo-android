package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.aurora.AuroraErrorLabel
import org.siloserver.silo.android.ui.components.aurora.AuroraEyebrow
import org.siloserver.silo.android.ui.components.aurora.AuroraGhostButton
import org.siloserver.silo.android.ui.components.aurora.AuroraPrimaryButton
import org.siloserver.silo.android.ui.components.aurora.AuroraScreen
import org.siloserver.silo.android.ui.components.aurora.AuroraScrim
import org.siloserver.silo.android.ui.components.aurora.AuroraTextField
import org.siloserver.silo.android.ui.components.aurora.AuroraVariant
import org.siloserver.silo.android.ui.components.aurora.auroraGlass

/**
 * Emailed-invitation claim: the deep link carried the server and token, so
 * this screen asks for a password and nothing else. Everything visual
 * mirrors SignupScreen's Aurora treatment.
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
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(serverUrl, token) {
        viewModel.load(serverUrl, token)
    }

    LaunchedEffect(state.claimSuccess) {
        if (state.claimSuccess) {
            viewModel.onClaimSuccessConsumed()
            onClaimComplete()
        }
    }

    AuroraScreen(variant = AuroraVariant.SignIn, scrim = AuroraScrim.Soft) {
        SiloLogo()
        Spacer(Modifier.height(30.dp))

        when {
            state.isLoadingInvitation -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(60.dp))
                    CircularProgressIndicator(color = Color(0xFFF3EFE9))
                }
            }

            state.invitationInvalid -> {
                AuroraEyebrow(text = "Invitation", centered = true)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "This invite has expired",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF3EFE9),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "The link may have been used already, revoked, or simply expired. " +
                        "Ask whoever invited you to send a fresh one.",
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(24.dp))
                AuroraGhostButton(
                    label = "Back to sign in",
                    onClick = onNavigateToLogin,
                    fillMaxWidth = true,
                )
            }

            else -> {
                val invitation = state.invitation ?: return@AuroraScreen
                invitation.inviterName?.let {
                    AuroraEyebrow(text = "Invited by $it", centered = true)
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = "Welcome to ${invitation.serverName}",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF3EFE9),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Choose a password and you're in. You'll sign in with your email address.",
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(24.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .auroraGlass(cornerRadius = 24.dp, emphasized = true)
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // The address is fixed by the invitation; show it, don't edit it.
                    Column {
                        Text(
                            text = "EMAIL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.55f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = invitation.email,
                            fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                    AuroraTextField(
                        label = "Password",
                        value = state.password,
                        onValueChange = viewModel::onPasswordChanged,
                        placeholder = "••••••",
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = Color.White.copy(alpha = 0.62f),
                                )
                            }
                        },
                    )
                    AuroraTextField(
                        label = "Confirm password",
                        value = state.confirmPassword,
                        onValueChange = viewModel::onConfirmPasswordChanged,
                        placeholder = "••••••",
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                        onImeAction = viewModel::onClaimClick,
                        visualTransformation = PasswordVisualTransformation(),
                    )

                    state.error?.let { AuroraErrorLabel(it) }

                    AuroraPrimaryButton(
                        label = if (state.isSubmitting) "Creating…" else "Create account",
                        onClick = viewModel::onClaimClick,
                        isLoading = state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
