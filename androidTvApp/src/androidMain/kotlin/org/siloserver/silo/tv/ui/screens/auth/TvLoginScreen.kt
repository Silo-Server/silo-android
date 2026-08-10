package org.siloserver.silo.tv.ui.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.AuroraEyebrow
import org.siloserver.silo.tv.ui.components.AuroraGhostButton
import org.siloserver.silo.tv.ui.components.AuroraJourneyProgress
import org.siloserver.silo.tv.ui.components.AuroraPrimaryButton
import org.siloserver.silo.tv.ui.components.AuroraStepRow
import org.siloserver.silo.tv.ui.components.auroraGlass
import org.siloserver.silo.tv.ui.components.auroraPanel
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
 * Sign-in form — compact, TOP-anchored so the username/password fields stay
 * above the on-screen IME. See [TvServerSetupScreen] for the rationale: on
 * Android TV the soft keyboard eats the lower half of the viewport, so any
 * centered form hides its inputs.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLoginScreen(
    onLoginSuccess: () -> Unit,
    onCreateAccount: () -> Unit = {},
    onChangeServer: () -> Unit = {},
    signupEnabled: Boolean = false,
    viewModel: TvLoginViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val deviceState by viewModel.deviceLoginState.collectAsState()
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val usePasswordFocus = remember { FocusRequester() }
    val signInFocus = remember { FocusRequester() }
    val backToPhoneFocus = remember { FocusRequester() }
    val changeServerFocus = remember { FocusRequester() }
    val formScrollState = rememberTvImeAwareFormScrollState()

    // Phone-first IA (mirrors tvOS TVLoginView): the QR device-login leads, and
    // the username/password form is one focus-step away behind "Use a password
    // instead". Nothing to type on the remote unless the viewer opts in.
    var showPasswordForm by remember { mutableStateOf(false) }

    LaunchedEffect(state.loginSuccess) {
        if (state.loginSuccess) {
            viewModel.onLoginSuccessConsumed()
            onLoginSuccess()
        }
    }
    // Default focus follows the active surface: the password form focuses the
    // username field; the phone-first surface focuses the "Use a password
    // instead" affordance so the remote never lands on a non-actionable QR.
    var loginSurfaceHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(showPasswordForm) {
        // Acquisition on both branches: the surface has just swapped, so
        // nothing on it holds focus yet. A dropped claim on the phone-first
        // branch strands the remote on a QR code that cannot be actioned.
        val target = if (showPasswordForm) usernameFocus else usePasswordFocus
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = target::requestFocus,
            isFocused = { loginSurfaceHasFocus },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Either branch's target lives under this root, so "focus is on the
            // login surface" is the criterion both claims are protecting.
            .onFocusChanged { loginSurfaceHasFocus = it.hasFocus }
            .imePadding(),
    ) {
        TvAuroraBackdrop(variant = TvAuroraVariant.SignIn)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .verticalScroll(formScrollState)
                .padding(
                    top = if (showPasswordForm) 20.dp else 32.dp,
                    bottom = 32.dp,
                    start = 54.dp,
                    end = 54.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandHeader()
                AuroraJourneyProgress(
                    currentStep = 2,
                    modifier = Modifier.width(230.dp),
                )
            }

            Spacer(modifier = Modifier.height(if (showPasswordForm) Spacing.sm else Spacing.lg))

            AuroraEyebrow(text = "Account")
            Spacer(modifier = Modifier.height(if (showPasswordForm) Spacing.md else Spacing.xl))

            if (showPasswordForm) {
                CredentialFormCard(
                    state = state,
                    usernameFocus = usernameFocus,
                    passwordFocus = passwordFocus,
                    signInFocus = signInFocus,
                    backToPhoneFocus = backToPhoneFocus,
                    changeServerFocus = changeServerFocus,
                    onUsernameChanged = viewModel::onUsernameChanged,
                    onPasswordChanged = viewModel::onPasswordChanged,
                    onLoginClick = viewModel::onLoginClick,
                    signupEnabled = signupEnabled,
                    onCreateAccount = onCreateAccount,
                    onBackToPhone = { showPasswordForm = false },
                    onChangeServer = onChangeServer,
                    modifier = Modifier.width(400.dp),
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxWidth(),
                ) {
                    PhoneSignInHero(
                        state = deviceState,
                        modifier = Modifier.width(430.dp),
                    )

                    QrLoginCard(
                        state = deviceState,
                        onRetry = viewModel::restartDeviceLogin,
                        onUsePassword = { showPasswordForm = true },
                        onChangeServer = onChangeServer,
                        usePasswordFocus = usePasswordFocus,
                        modifier = Modifier.width(300.dp),
                    )
                }
            }
        }

    }
}

/**
 * Left-hand hero for the phone-first sign-in: eyebrow already sits above; this
 * is the headline, the lede, the three numbered steps, and a live "waiting"
 * status while the device-login session is pending. Mirrors tvOS
 * `TVLoginView.heroColumn`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PhoneSignInHero(
    state: DeviceLoginRepository.DeviceLoginState,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier,
    ) {
        Text(
            text = "Scan. Confirm.\nStart watching.",
            style = TvLoginTextStyles.Hero,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Open your phone's Camera and point it at the code. " +
                "You won't need to type a password on your TV.",
            style = TvLoginTextStyles.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        AuroraStepRow(number = 1, text = "Scan with your phone's camera")
        AuroraStepRow(number = 2, text = "Confirm the matching number")
        AuroraStepRow(number = 3, text = "Approve on your phone — you're in")

        if (state is DeviceLoginRepository.DeviceLoginState.Awaiting) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "Waiting for approval…",
                style = TvLoginTextStyles.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CredentialFormCard(
    state: TvLoginUiState,
    usernameFocus: FocusRequester,
    passwordFocus: FocusRequester,
    signInFocus: FocusRequester,
    backToPhoneFocus: FocusRequester,
    changeServerFocus: FocusRequester,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLoginClick: () -> Unit,
    signupEnabled: Boolean,
    onCreateAccount: () -> Unit,
    onBackToPhone: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .auroraPanel(20.dp)
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = "Sign in",
                style = TvLoginTextStyles.Title,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Use the account from your Silo server.",
                style = TvLoginTextStyles.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Username — a mono uppercase caption labels each field, matching the
        // server-setup card; the Material floating label is dropped so nothing
        // floats oversized in the border notch.
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.tvImeAwareFieldContext(),
        ) {
            Text(
                text = "USERNAME",
                style = TvLoginTextStyles.InputLabel,
                color = Color.White.copy(alpha = 0.52f),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChanged,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                enabled = !state.isLoading,
                textStyle = TvLoginTextStyles.Field,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .focusRequester(usernameFocus),
                colors = tvOutlinedTextFieldColors(),
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.tvImeAwareFieldContext(),
        ) {
            Text(
                text = "PASSWORD",
                style = TvLoginTextStyles.InputLabel,
                color = Color.White.copy(alpha = 0.52f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChanged,
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            enabled = !state.isLoading,
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSubmitTvCredentialLogin(state.username, state.password, state.isLoading)) {
                                onLoginClick()
                            }
                        },
                    ),
                    enabled = !state.isLoading,
                    textStyle = TvLoginTextStyles.Field,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .focusRequester(passwordFocus),
                    colors = tvOutlinedTextFieldColors(),
                )
            }
        }

        if (state.error != null) {
            Text(
                text = state.error!!,
                style = TvLoginTextStyles.Error,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Box {
            AuroraPrimaryButton(
                label = if (state.isLoading) "Signing in…" else "Sign In",
                icon = Icons.AutoMirrored.Filled.Login,
                onClick = onLoginClick,
                focusRequester = signInFocus,
                focusHalo = false,
                filledAtRest = false,
                neutralFocusFill = true,
                enabled = !state.isLoading,
                modifier = Modifier
                    .focusProperties {
                        down = backToPhoneFocus
                    }
                    .fillMaxWidth()
                    .height(64.dp),
            )
        }

        // Surfaced only when the server reports public signup is enabled. The
        // ServerSetup probe forwards that flag through the Login route so this
        // affordance never appears on signup-disabled servers.
        if (signupEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = "Don't have an account yet?",
                    style = TvLoginTextStyles.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvHeroActionPill(
                    label = "Create Account",
                    icon = Icons.Default.AccountCircle,
                    variant = TvPillVariant.Hollow,
                    heightOverride = 36.dp,
                    horizontalPaddingOverride = 18.dp,
                    labelStyle = TvLoginTextStyles.Button,
                    onClick = onCreateAccount,
                )
            }
        }

        // Return to the phone-first surface (the QR pairing remains live), or
        // bail out to server setup to point this TV at a different server —
        // both affordances mirror tvOS TVLoginView. Stacked full-width like the
        // QR pane so the long "Back to phone sign-in" label never wraps.
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AuroraGhostButton(
                label = "Back to phone sign-in",
                onClick = onBackToPhone,
                fontSize = 18.sp,
                horizontalPadding = 18.dp,
                verticalPadding = 8.dp,
                modifier = Modifier
                    .focusRequester(backToPhoneFocus)
                    .focusProperties {
                        up = signInFocus
                        down = changeServerFocus
                    }
                    .fillMaxWidth(),
            )
            AuroraGhostButton(
                label = "Change server",
                onClick = onChangeServer,
                fontSize = 18.sp,
                horizontalPadding = 18.dp,
                verticalPadding = 8.dp,
                modifier = Modifier
                    .focusRequester(changeServerFocus)
                    .focusProperties {
                        up = backToPhoneFocus
                    }
                    .fillMaxWidth(),
            )
        }
    }
}

private object TvLoginTextStyles {
    val Hero = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    )

    val Title = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    )

    val Body = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    val Field = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        color = Color.White,
    )

    /** Mono uppercase caption that labels each input — mirrors server setup. */
    val InputLabel = TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 19.sp,
        letterSpacing = 3.sp,
    )

    val Error = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )

    val Button = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
}

/**
 * Live QR pane bound to the device-login state machine. Renders one of five
 * branches based on [state]:
 *
 *  - Idle / Initiating → spinner copy + empty 320dp box (matches the QR's
 *    final footprint so the layout doesn't reflow when the matrix lands).
 *  - Awaiting → the actual QR (encoded `verification_uri_complete`) plus
 *    the short `user_code` underneath as a typing fallback.
 *  - Approved → "Signed in!" — short-lived, the screen-level
 *    `LaunchedEffect(loginSuccess)` navigates away.
 *  - Failed → message + "Try again" pill that fires [onRetry].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QrLoginCard(
    state: DeviceLoginRepository.DeviceLoginState,
    onRetry: () -> Unit,
    onUsePassword: () -> Unit,
    onChangeServer: () -> Unit,
    usePasswordFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .auroraGlass(15.dp)
            .padding(24.dp),
    ) {
        Text(
            text = "Scan with Camera",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        when (state) {
            DeviceLoginRepository.DeviceLoginState.Idle,
            DeviceLoginRepository.DeviceLoginState.Initiating -> {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .background(
                            Color.White.copy(alpha = 0.06f),
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(8.dp),
                        ),
                )
                Text(
                    text = "Loading pairing code…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is DeviceLoginRepository.DeviceLoginState.Awaiting -> {
                QrCodePanel(
                    content = state.session.verificationUriComplete,
                    size = 150.dp,
                )
                MatchCodeTiles(code = state.session.matchCode)
            }
            is DeviceLoginRepository.DeviceLoginState.Approved -> {
                Text(
                    text = "Signed in!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            is DeviceLoginRepository.DeviceLoginState.Failed -> {
                Text(
                    text = state.message ?: "Sign-in failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TvHeroActionPill(
                    label = "Try again",
                    icon = Icons.Default.Refresh,
                    variant = TvPillVariant.Hollow,
                    onClick = onRetry,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )
        Spacer(modifier = Modifier.height(Spacing.xs))

        AuroraGhostButton(
            label = "Sign in with a password",
            onClick = onUsePassword,
            modifier = Modifier.focusRequester(usePasswordFocus),
        )
        AuroraGhostButton(
            label = "Use another server",
            onClick = onChangeServer,
        )
    }
}

/**
 * Match-code confirmation tiles — "CONFIRM THIS CODE" over the server-issued
 * code, one monospaced tile per character. Mirrors tvOS
 * `TVLoginView.matchCodeTiles`; word/number separators render as a thin dash.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MatchCodeTiles(code: String, modifier: Modifier = Modifier) {
    if (code.isBlank()) return
    val tileWidthDp = matchCodeTileWidthDp(code)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = modifier,
    ) {
        Text(
            text = "CONFIRM THIS CODE",
            style = TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 3.sp,
            ),
            color = Color.White.copy(alpha = 0.6f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MATCH_CODE_TILE_GAP_DP.dp)) {
            code.uppercase().forEach { ch ->
                val isSep = ch == '-' || ch == ' '
                if (isSep) {
                    Text(
                        text = "–",
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.width(MATCH_CODE_SEPARATOR_WIDTH_DP.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = tileWidthDp.dp, height = 30.dp)
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ch.toString(),
                            style = TextStyle(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

internal const val MATCH_CODE_TILE_WIDTH_DP = 24
internal const val MATCH_CODE_SEPARATOR_WIDTH_DP = 10
internal const val MATCH_CODE_TILE_GAP_DP = 2
internal const val MATCH_CODE_CONTENT_WIDTH_DP = 252

internal fun matchCodeTileWidthDp(code: String): Int {
    val tileCount = code.count { ch -> ch != '-' && ch != ' ' }
    if (tileCount == 0) return MATCH_CODE_TILE_WIDTH_DP

    val separatorCount = code.length - tileCount
    val gapWidth = (code.length - 1).coerceAtLeast(0) * MATCH_CODE_TILE_GAP_DP
    val availableTileWidth = (
        MATCH_CODE_CONTENT_WIDTH_DP -
            separatorCount * MATCH_CODE_SEPARATOR_WIDTH_DP -
            gapWidth
        ).coerceAtLeast(tileCount)
    return minOf(MATCH_CODE_TILE_WIDTH_DP, availableTileWidth / tileCount)
}

internal fun matchCodeRowWidthDp(code: String): Int {
    if (code.isEmpty()) return 0
    val tileWidth = matchCodeTileWidthDp(code)
    val characterWidth = code.sumOf { ch ->
        if (ch == '-' || ch == ' ') MATCH_CODE_SEPARATOR_WIDTH_DP else tileWidth
    }
    return characterWidth + (code.length - 1) * MATCH_CODE_TILE_GAP_DP
}
