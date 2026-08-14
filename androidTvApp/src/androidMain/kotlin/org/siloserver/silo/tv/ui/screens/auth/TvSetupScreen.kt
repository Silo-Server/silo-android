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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.TvAuroraBackdrop
import org.siloserver.silo.tv.ui.components.TvAuroraVariant
import org.siloserver.silo.tv.ui.components.AuroraPrimaryButton
import org.siloserver.silo.tv.ui.components.rememberTvImeAwareFormScrollState
import org.siloserver.silo.tv.ui.components.tvImeAwareFieldContext
import org.siloserver.silo.tv.ui.components.tvShowImeOnSelect
import org.siloserver.silo.tv.ui.components.TvAuthFormDefaults
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * First-time server setup — creates the initial admin account on a freshly
 * installed Silo server. Mirrors the phone's `SetupScreen` logic via
 * [TvSetupViewModel]; on success the user is already signed in (tokens
 * persisted by [org.siloserver.silo.repository.AuthRepository.setup]) so the
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
    val formScrollState = rememberTvImeAwareFormScrollState()

    LaunchedEffect(state.setupSuccess) {
        if (state.setupSuccess) {
            viewModel.onSetupSuccessConsumed()
            onSetupComplete()
        }
    }
    // A text field on a first-run screen: if this claim is dropped the
    // remote has nothing to act on and no touch fallback exists.
    // Snapshot-backed input mode drives the claim: null contentKey while the
    // viewer is in touch mode (a programmatic claim on a text field pops the
    // IME; pointer users click the field themselves), and the key change on
    // flipping back to key input re-runs the claim so the D-pad always has
    // somewhere to land.
    val inputMode = LocalInputModeManager.current.inputMode
    val usernameFocusModifier = rememberTvContentInitialFocus(
        target = usernameFocus,
        contentKey = if (inputMode == InputMode.Touch) null else inputMode,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(usernameFocusModifier)
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
                .verticalScroll(formScrollState)
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

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvImeAwareFieldContext(),
            ) {
                Text(
                    text = "USERNAME",
                    style = TvAuthFormTextStyles.InputLabel,
                    color = Color.White.copy(alpha = 0.52f),
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChanged,
                    singleLine = true,
                    textStyle = TvAuthFormTextStyles.FieldText,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        showKeyboardOnFocus = false,
                    ),
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvAuthFormDefaults.FieldHeight)
                        .tvShowImeOnSelect()
                        .focusRequester(usernameFocus),
                    colors = tvOutlinedTextFieldColors(),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvImeAwareFieldContext(),
            ) {
                Text(
                    text = "EMAIL",
                    style = TvAuthFormTextStyles.InputLabel,
                    color = Color.White.copy(alpha = 0.52f),
                )
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChanged,
                    singleLine = true,
                    textStyle = TvAuthFormTextStyles.FieldText,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        showKeyboardOnFocus = false,
                    ),
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvAuthFormDefaults.FieldHeight)
                        .tvShowImeOnSelect(),
                    colors = tvOutlinedTextFieldColors(),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvImeAwareFieldContext(),
            ) {
                Text(
                    text = "PASSWORD",
                    style = TvAuthFormTextStyles.InputLabel,
                    color = Color.White.copy(alpha = 0.52f),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChanged,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = TvAuthFormTextStyles.FieldText,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        showKeyboardOnFocus = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (!state.isLoading) viewModel.onCreateAccountClick() },
                    ),
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvAuthFormDefaults.FieldHeight)
                        .tvShowImeOnSelect(),
                    colors = tvOutlinedTextFieldColors(),
                )
            }

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    style = TvAuthFormTextStyles.Error,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Box {
                AuroraPrimaryButton(
                    label = if (state.isLoading) "Creating account…" else "Create Account",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    enabled = !state.isLoading,
                    onClick = viewModel::onCreateAccountClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvAuthFormDefaults.PrimaryButtonHeight),
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
    /** Mono uppercase caption above each input — the auth-flow field idiom
     *  (server setup and sign-in); Material's floating label renders oversized
     *  in the border notch at TV type scale. */
    val InputLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 19.sp,
        letterSpacing = 3.sp,
    )
    val FieldText = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        color = Color.White,
    )
    val Error = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    )
}
