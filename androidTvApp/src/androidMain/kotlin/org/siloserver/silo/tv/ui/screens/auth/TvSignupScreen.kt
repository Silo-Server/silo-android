package org.siloserver.silo.tv.ui.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.TvAuroraBackdrop
import org.siloserver.silo.tv.ui.components.TvAuroraVariant
import org.siloserver.silo.tv.ui.components.TvHeroActionPill
import org.siloserver.silo.tv.ui.components.TvPillVariant
import org.siloserver.silo.tv.ui.components.rememberTvImeAwareFormScrollState
import org.siloserver.silo.tv.ui.components.tvImeAwareFieldContext
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * New-user registration with an invite code. Mirrors the phone's `SignupScreen`
 * via [TvSignupViewModel]; reachable only when the server reports signup is
 * enabled (the login screen surfaces the entry point). On success the user is
 * signed in (tokens persisted by [org.siloserver.silo.repository.AuthRepository.signup])
 * so the flow advances to profile selection.
 *
 * Layout follows [TvServerSetupScreen]/[TvSetupScreen]: TOP-anchored +
 * `imePadding` + `bringIntoView` so the focused field stays above the IME.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSignupScreen(
    onSignupComplete: () -> Unit,
    onBackToLogin: () -> Unit,
    viewModel: TvSignupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val usernameFocus = remember { FocusRequester() }
    val formScrollState = rememberTvImeAwareFormScrollState()

    LaunchedEffect(state.signupSuccess) {
        if (state.signupSuccess) {
            viewModel.onSignupSuccessConsumed()
            onSignupComplete()
        }
    }
    // A text field on a first-run screen: if this claim is dropped the
    // remote has nothing to act on and no touch fallback exists.
    val usernameFocusModifier = rememberTvContentInitialFocus(
        target = usernameFocus,
        contentKey = Unit,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(usernameFocusModifier)
            .imePadding(),
    ) {
        TvAuroraBackdrop(variant = TvAuroraVariant.SignIn)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(420.dp)
                .fillMaxSize()
                .verticalScroll(formScrollState)
                .padding(top = 24.dp, bottom = Spacing.lg, start = Spacing.xl, end = Spacing.xl),
        ) {
            BrandHeader()

            Spacer(modifier = Modifier.height(Spacing.xs))

            Text(
                text = "Create Account",
                style = TvAuthFormTextStyles.Title,
                color = Color.White,
            )
            Text(
                text = "Join this Silo server with your invite.",
                style = TvAuthFormTextStyles.Body,
                color = Color.White.copy(alpha = 0.72f),
            )

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChanged,
                label = { Text("Username", style = TvAuthFormTextStyles.FieldLabel, color = Color.White) },
                singleLine = true,
                textStyle = TvAuthFormTextStyles.FieldText,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvImeAwareFieldContext()
                    .focusRequester(usernameFocus),
                colors = tvOutlinedTextFieldColors(),
            )

            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChanged,
                label = { Text("Email", style = TvAuthFormTextStyles.FieldLabel, color = Color.White) },
                singleLine = true,
                textStyle = TvAuthFormTextStyles.FieldText,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvImeAwareFieldContext(),
                colors = tvOutlinedTextFieldColors(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChanged,
                label = { Text("Password", style = TvAuthFormTextStyles.FieldLabel, color = Color.White) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TvAuthFormTextStyles.FieldText,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvImeAwareFieldContext(),
                colors = tvOutlinedTextFieldColors(),
            )

            OutlinedTextField(
                value = state.inviteCode,
                onValueChange = viewModel::onInviteCodeChanged,
                label = { Text("Invite Code", style = TvAuthFormTextStyles.FieldLabel, color = Color.White) },
                singleLine = true,
                textStyle = TvAuthFormTextStyles.FieldText,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (!state.isLoading) viewModel.onSignupClick() },
                ),
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvImeAwareFieldContext(),
                colors = tvOutlinedTextFieldColors(),
            )

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    style = TvAuthFormTextStyles.Error,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box {
                    TvHeroActionPill(
                        label = if (state.isLoading) "Signing up…" else "Sign Up",
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        variant = TvPillVariant.Filled,
                        heightOverride = 32.dp,
                        horizontalPaddingOverride = 19.dp,
                        labelStyle = TvAuthFormTextStyles.Button,
                        enabled = !state.isLoading,
                        onClick = viewModel::onSignupClick,
                    )
                }
                TvHeroActionPill(
                    label = "Sign In Instead",
                    icon = Icons.AutoMirrored.Filled.Login,
                    variant = TvPillVariant.Hollow,
                    heightOverride = 32.dp,
                    horizontalPaddingOverride = 19.dp,
                    labelStyle = TvAuthFormTextStyles.Button,
                    onClick = onBackToLogin,
                )
            }
        }

    }
}

/** Compact horizontal brand row, matching the other auth screens. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Image(
            painter = painterResource(id = R.drawable.silo_wordmark),
            contentDescription = "Silo",
            modifier = Modifier
                .width(66.dp)
                .height(35.dp),
            contentScale = ContentScale.Fit,
        )
    }
}
