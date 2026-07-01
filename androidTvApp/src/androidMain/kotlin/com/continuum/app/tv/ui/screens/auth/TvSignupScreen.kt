package com.continuum.app.tv.ui.screens.auth

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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.continuum.app.tv.R
import com.continuum.app.tv.ui.components.TvAuroraBackdrop
import com.continuum.app.tv.ui.components.TvAuroraVariant
import com.continuum.app.tv.ui.components.TvHeroActionPill
import com.continuum.app.tv.ui.components.TvPillVariant
import com.continuum.app.tv.ui.components.TvAnsiKeyboard
import com.continuum.app.tv.ui.components.TvAnsiKeyboardAction
import com.continuum.app.tv.ui.components.applyTvAnsiKeyboardAction
import com.continuum.app.tv.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * New-user registration with an invite code. Mirrors the phone's `SignupScreen`
 * via [TvSignupViewModel]; reachable only when the server reports signup is
 * enabled (the login screen surfaces the entry point). On success the user is
 * signed in (tokens persisted by [com.continuum.app.repository.AuthRepository.signup])
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
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val inviteFocus = remember { FocusRequester() }
    val keyboardFirstKeyFocus = remember { FocusRequester() }
    val usernameBringIntoView = remember { BringIntoViewRequester() }
    val emailBringIntoView = remember { BringIntoViewRequester() }
    val passwordBringIntoView = remember { BringIntoViewRequester() }
    val inviteBringIntoView = remember { BringIntoViewRequester() }
    val submitBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var activeField by remember { mutableStateOf(TvSignupFormField.Username) }
    var isKeyboardVisible by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var keyboardFocusPulse by remember { mutableStateOf(0) }

    LaunchedEffect(state.signupSuccess) {
        if (state.signupSuccess) {
            viewModel.onSignupSuccessConsumed()
            onSignupComplete()
        }
    }
    LaunchedEffect(Unit) { runCatching { usernameFocus.requestFocus() } }
    LaunchedEffect(isKeyboardVisible, keyboardFocusPulse) {
        if (isKeyboardVisible) {
            delay(120)
            runCatching { keyboardFirstKeyFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                .verticalScroll(rememberScrollState())
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

            CredentialDisplayField(
                value = state.username,
                hint = "Username",
                enabled = !state.isLoading,
                isActive = activeField == TvSignupFormField.Username,
                isPassword = false,
                passwordVisible = true,
                focusRequester = usernameFocus,
                onFocused = {
                    activeField = TvSignupFormField.Username
                    scope.launch { usernameBringIntoView.bringIntoView() }
                },
                onOpenKeyboard = {
                    activeField = TvSignupFormField.Username
                    isKeyboardVisible = true
                    keyboardFocusPulse++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .bringIntoViewRequester(usernameBringIntoView),
            )

            CredentialDisplayField(
                value = state.email,
                hint = "Email",
                enabled = !state.isLoading,
                isActive = activeField == TvSignupFormField.Email,
                isPassword = false,
                passwordVisible = true,
                focusRequester = emailFocus,
                onFocused = {
                    activeField = TvSignupFormField.Email
                    scope.launch { emailBringIntoView.bringIntoView() }
                },
                onOpenKeyboard = {
                    activeField = TvSignupFormField.Email
                    isKeyboardVisible = true
                    keyboardFocusPulse++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .bringIntoViewRequester(emailBringIntoView),
            )

            CredentialDisplayField(
                value = state.password,
                hint = "Password",
                enabled = !state.isLoading,
                isActive = activeField == TvSignupFormField.Password,
                isPassword = true,
                passwordVisible = passwordVisible,
                focusRequester = passwordFocus,
                onFocused = {
                    activeField = TvSignupFormField.Password
                    scope.launch { passwordBringIntoView.bringIntoView() }
                },
                onOpenKeyboard = {
                    activeField = TvSignupFormField.Password
                    isKeyboardVisible = true
                    keyboardFocusPulse++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .bringIntoViewRequester(passwordBringIntoView),
            )

            CredentialDisplayField(
                value = state.inviteCode,
                hint = "Invite Code",
                enabled = !state.isLoading,
                isActive = activeField == TvSignupFormField.InviteCode,
                isPassword = false,
                passwordVisible = true,
                focusRequester = inviteFocus,
                onFocused = {
                    activeField = TvSignupFormField.InviteCode
                    scope.launch { inviteBringIntoView.bringIntoView() }
                },
                onOpenKeyboard = {
                    activeField = TvSignupFormField.InviteCode
                    isKeyboardVisible = true
                    keyboardFocusPulse++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .bringIntoViewRequester(inviteBringIntoView),
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
                Box(
                    modifier = Modifier
                        .bringIntoViewRequester(submitBringIntoView)
                        .onFocusEvent { fs -> if (fs.hasFocus) scope.launch { submitBringIntoView.bringIntoView() } },
                ) {
                    TvHeroActionPill(
                        label = if (state.isLoading) "Signing up…" else "Sign Up",
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        variant = TvPillVariant.Filled,
                        heightOverride = 32.dp,
                        horizontalPaddingOverride = 19.dp,
                        labelStyle = TvAuthFormTextStyles.Button,
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

        if (isKeyboardVisible) {
            TvSignupAnsiKeyboard(
                activeField = activeField,
                enabled = !state.isLoading,
                passwordVisible = passwordVisible,
                firstKeyFocusRequester = keyboardFirstKeyFocus,
                onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                onAction = { field, action ->
                    when (field) {
                        TvSignupFormField.Username -> viewModel.onUsernameChanged(
                            applyTvAnsiKeyboardAction(state.username, action, TV_SIGNUP_FORM_MAX_LENGTH),
                        )
                        TvSignupFormField.Email -> viewModel.onEmailChanged(
                            applyTvAnsiKeyboardAction(state.email, action, TV_SIGNUP_FORM_MAX_LENGTH),
                        )
                        TvSignupFormField.Password -> viewModel.onPasswordChanged(
                            applyTvAnsiKeyboardAction(state.password, action, TV_SIGNUP_FORM_MAX_LENGTH),
                        )
                        TvSignupFormField.InviteCode -> viewModel.onInviteCodeChanged(
                            applyTvAnsiKeyboardAction(state.inviteCode, action, TV_SIGNUP_FORM_MAX_LENGTH),
                        )
                    }
                },
                onNext = {
                    when (activeField) {
                        TvSignupFormField.Username -> activeField = TvSignupFormField.Email
                        TvSignupFormField.Email -> activeField = TvSignupFormField.Password
                        TvSignupFormField.Password -> activeField = TvSignupFormField.InviteCode
                        TvSignupFormField.InviteCode -> {
                            isKeyboardVisible = false
                            viewModel.onSignupClick()
                        }
                    }
                    keyboardFocusPulse++
                },
                onDismiss = {
                    isKeyboardVisible = false
                    when (activeField) {
                        TvSignupFormField.Username -> runCatching { usernameFocus.requestFocus() }
                        TvSignupFormField.Email -> runCatching { emailFocus.requestFocus() }
                        TvSignupFormField.Password -> runCatching { passwordFocus.requestFocus() }
                        TvSignupFormField.InviteCode -> runCatching { inviteFocus.requestFocus() }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 48.dp, end = 48.dp, bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun TvSignupAnsiKeyboard(
    activeField: TvSignupFormField,
    enabled: Boolean,
    passwordVisible: Boolean,
    firstKeyFocusRequester: FocusRequester,
    onTogglePasswordVisibility: () -> Unit,
    onAction: (TvSignupFormField, TvAnsiKeyboardAction) -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvAnsiKeyboard(
        primaryLabel = if (activeField == TvSignupFormField.InviteCode) "Sign Up" else "Next",
        primaryEnabled = enabled,
        enabled = enabled,
        firstKeyFocusRequester = firstKeyFocusRequester,
        onAction = { action -> onAction(activeField, action) },
        onPrimary = onNext,
        onDismiss = onDismiss,
        modifier = modifier,
        showPasswordVisibilityKey = activeField == TvSignupFormField.Password,
        passwordVisible = passwordVisible,
        onTogglePasswordVisibility = onTogglePasswordVisibility,
    )
}

private enum class TvSignupFormField {
    Username,
    Email,
    Password,
    InviteCode,
}

private const val TV_SIGNUP_FORM_MAX_LENGTH = 200

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
