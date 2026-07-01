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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * First-time server setup — creates the initial admin account on a freshly
 * installed Silo server. Mirrors the phone's `SetupScreen` logic via
 * [TvSetupViewModel]; on success the user is already signed in (tokens
 * persisted by [com.continuum.app.repository.AuthRepository.setup]) so the
 * flow advances to profile selection.
 *
 * Layout follows [TvServerSetupScreen]: TOP-anchored + `imePadding` +
 * `bringIntoView` so the focused field stays above the soft IME on Android TV.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: TvSetupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val usernameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val keyboardFirstKeyFocus = remember { FocusRequester() }
    val usernameBringIntoView = remember { BringIntoViewRequester() }
    val emailBringIntoView = remember { BringIntoViewRequester() }
    val passwordBringIntoView = remember { BringIntoViewRequester() }
    val submitBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var activeField by remember { mutableStateOf(TvSetupFormField.Username) }
    var isKeyboardVisible by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var keyboardFocusPulse by remember { mutableStateOf(0) }

    LaunchedEffect(state.setupSuccess) {
        if (state.setupSuccess) {
            viewModel.onSetupSuccessConsumed()
            onSetupComplete()
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
        TvAuroraBackdrop(variant = TvAuroraVariant.Welcome)
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
                text = "Welcome to Silo",
                style = TvAuthFormTextStyles.Title,
                color = Color.White,
            )
            Text(
                text = "Create your first admin account.",
                style = TvAuthFormTextStyles.Body,
                color = Color.White.copy(alpha = 0.72f),
            )

            CredentialDisplayField(
                value = state.username,
                hint = "Username",
                enabled = !state.isLoading,
                isActive = activeField == TvSetupFormField.Username,
                isPassword = false,
                passwordVisible = true,
                focusRequester = usernameFocus,
                onFocused = {
                    activeField = TvSetupFormField.Username
                    scope.launch { usernameBringIntoView.bringIntoView() }
                },
                onOpenKeyboard = {
                    activeField = TvSetupFormField.Username
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
                isActive = activeField == TvSetupFormField.Email,
                isPassword = false,
                passwordVisible = true,
                focusRequester = emailFocus,
                onFocused = {
                    activeField = TvSetupFormField.Email
                    scope.launch { emailBringIntoView.bringIntoView() }
                },
                onOpenKeyboard = {
                    activeField = TvSetupFormField.Email
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
                isActive = activeField == TvSetupFormField.Password,
                isPassword = true,
                passwordVisible = passwordVisible,
                focusRequester = passwordFocus,
                onFocused = {
                    activeField = TvSetupFormField.Password
                    scope.launch { passwordBringIntoView.bringIntoView() }
                },
                onOpenKeyboard = {
                    activeField = TvSetupFormField.Password
                    isKeyboardVisible = true
                    keyboardFocusPulse++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .bringIntoViewRequester(passwordBringIntoView),
            )

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    style = TvAuthFormTextStyles.Error,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Box(
                modifier = Modifier
                    .bringIntoViewRequester(submitBringIntoView)
                    .onFocusEvent { fs -> if (fs.hasFocus) scope.launch { submitBringIntoView.bringIntoView() } },
            ) {
                TvHeroActionPill(
                    label = if (state.isLoading) "Creating account…" else "Create Account",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    variant = TvPillVariant.Filled,
                    heightOverride = 32.dp,
                    horizontalPaddingOverride = 19.dp,
                    labelStyle = TvAuthFormTextStyles.Button,
                    onClick = viewModel::onCreateAccountClick,
                )
            }
        }

        if (isKeyboardVisible) {
            TvSetupAnsiKeyboard(
                activeField = activeField,
                enabled = !state.isLoading,
                passwordVisible = passwordVisible,
                firstKeyFocusRequester = keyboardFirstKeyFocus,
                onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                onAction = { field, action ->
                    when (field) {
                        TvSetupFormField.Username -> viewModel.onUsernameChanged(
                            applyTvAnsiKeyboardAction(state.username, action, TV_AUTH_FORM_MAX_LENGTH),
                        )
                        TvSetupFormField.Email -> viewModel.onEmailChanged(
                            applyTvAnsiKeyboardAction(state.email, action, TV_AUTH_FORM_MAX_LENGTH),
                        )
                        TvSetupFormField.Password -> viewModel.onPasswordChanged(
                            applyTvAnsiKeyboardAction(state.password, action, TV_AUTH_FORM_MAX_LENGTH),
                        )
                    }
                },
                onNext = {
                    when (activeField) {
                        TvSetupFormField.Username -> activeField = TvSetupFormField.Email
                        TvSetupFormField.Email -> activeField = TvSetupFormField.Password
                        TvSetupFormField.Password -> {
                            isKeyboardVisible = false
                            viewModel.onCreateAccountClick()
                        }
                    }
                    keyboardFocusPulse++
                },
                onDismiss = {
                    isKeyboardVisible = false
                    when (activeField) {
                        TvSetupFormField.Username -> runCatching { usernameFocus.requestFocus() }
                        TvSetupFormField.Email -> runCatching { emailFocus.requestFocus() }
                        TvSetupFormField.Password -> runCatching { passwordFocus.requestFocus() }
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
private fun TvSetupAnsiKeyboard(
    activeField: TvSetupFormField,
    enabled: Boolean,
    passwordVisible: Boolean,
    firstKeyFocusRequester: FocusRequester,
    onTogglePasswordVisibility: () -> Unit,
    onAction: (TvSetupFormField, TvAnsiKeyboardAction) -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvAnsiKeyboard(
        primaryLabel = if (activeField == TvSetupFormField.Password) "Create" else "Next",
        primaryEnabled = enabled,
        enabled = enabled,
        firstKeyFocusRequester = firstKeyFocusRequester,
        onAction = { action -> onAction(activeField, action) },
        onPrimary = onNext,
        onDismiss = onDismiss,
        modifier = modifier,
        showPasswordVisibilityKey = activeField == TvSetupFormField.Password,
        passwordVisible = passwordVisible,
        onTogglePasswordVisibility = onTogglePasswordVisibility,
    )
}

private enum class TvSetupFormField {
    Username,
    Email,
    Password,
}

private const val TV_AUTH_FORM_MAX_LENGTH = 200

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

/** Shared text styles for the setup + signup forms (mirrors TvServerSetup styling). */
internal object TvAuthFormTextStyles {
    val Title = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
    val Body = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    )
    val FieldLabel = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    )
    val FieldText = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        color = Color.White,
    )
    val Button = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    )
    val Error = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    )
}
