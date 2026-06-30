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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.continuum.app.tv.ui.components.tvOutlinedTextFieldColors
import com.continuum.app.tv.ui.theme.Spacing
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
    val usernameBringIntoView = remember { BringIntoViewRequester() }
    val emailBringIntoView = remember { BringIntoViewRequester() }
    val passwordBringIntoView = remember { BringIntoViewRequester() }
    val submitBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.setupSuccess) {
        if (state.setupSuccess) {
            viewModel.onSetupSuccessConsumed()
            onSetupComplete()
        }
    }
    LaunchedEffect(Unit) { runCatching { usernameFocus.requestFocus() } }

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
                    .bringIntoViewRequester(usernameBringIntoView)
                    .onFocusEvent { fs -> if (fs.isFocused) scope.launch { usernameBringIntoView.bringIntoView() } }
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
                    .bringIntoViewRequester(emailBringIntoView)
                    .onFocusEvent { fs -> if (fs.isFocused) scope.launch { emailBringIntoView.bringIntoView() } },
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
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (!state.isLoading) viewModel.onCreateAccountClick() },
                ),
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(passwordBringIntoView)
                    .onFocusEvent { fs -> if (fs.isFocused) scope.launch { passwordBringIntoView.bringIntoView() } },
                colors = tvOutlinedTextFieldColors(),
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
