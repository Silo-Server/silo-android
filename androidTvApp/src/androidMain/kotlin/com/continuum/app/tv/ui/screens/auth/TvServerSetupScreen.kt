package com.continuum.app.tv.ui.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.continuum.app.tv.R
import com.continuum.app.common.pairing.PairingReceiver
import com.continuum.app.common.pairing.PairingReceiverStatus
import com.continuum.app.common.pairing.TvPairingAdvertiser
import com.continuum.app.tv.ui.components.AuroraAccent
import com.continuum.app.tv.ui.components.AuroraEyebrow
import com.continuum.app.tv.ui.components.AuroraInk
import com.continuum.app.tv.ui.components.auroraGlass
import com.continuum.app.tv.ui.components.AuroraGhostButton
import com.continuum.app.tv.ui.components.AuroraPrimaryButton
import com.continuum.app.tv.ui.components.TvAuroraBackdrop
import com.continuum.app.tv.ui.components.TvAuroraVariant
import com.continuum.app.tv.ui.components.tvOutlinedTextFieldColors
import com.continuum.app.tv.ui.theme.Spacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Server setup — connects the app to a Silo server.
 *
 * While idle, this mirrors tvOS `TVServerSetupView`, scaled for the Shield's
 * ~960×540dp canvas (the iOS source is laid out in 1920×1080 points): the
 * wordmark sits top-left in normal flow, a centered eyebrow + title header
 * block sits below it, and the phone-card / OR / manual-card chooser is
 * centered in the remaining vertical space via a `weight(1f)` box. There are
 * no fixed row heights and no negative offsets — the weight box is what keeps
 * the title clear of the eyebrow and the phone headline clear of its
 * description. Once a phone connects, the chooser swaps in-place to the live
 * pairing receiver flow.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvServerSetupScreen(
    onContinueToLogin: (signupEnabled: Boolean) -> Unit,
    onNeedsSetup: () -> Unit,
    onPairedSignIn: () -> Unit = {},
    viewModel: TvServerSetupViewModel = koinViewModel(),
    pairingReceiver: PairingReceiver = koinInject(),
    pairingAdvertiser: TvPairingAdvertiser = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val pairingStatus by pairingReceiver.status.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val urlBringIntoView = remember { BringIntoViewRequester() }
    val connectBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val isActivePairing = pairingStatus.isActivePairing

    // Companion LAN pairing: advertise `_silopair._tcp` while this screen is on
    // so a phone running Silo can push the server URL + drive device-login,
    // sparing the viewer from typing a URL on the remote. Advertising stops
    // when the screen leaves the composition.
    DisposableEffect(Unit) {
        pairingAdvertiser.start()
        onDispose { pairingAdvertiser.stop() }
    }
    LaunchedEffect(pairingStatus) {
        if (pairingStatus is PairingReceiverStatus.Completed) {
            delay(1_800)
            pairingAdvertiser.stop()
            onPairedSignIn()
        }
    }

    LaunchedEffect(state.navigateTo) {
        when (val dest = state.navigateTo) {
            is TvServerSetupDestination.Setup -> {
                viewModel.onNavigationConsumed()
                onNeedsSetup()
            }
            is TvServerSetupDestination.Login -> {
                viewModel.onNavigationConsumed()
                onContinueToLogin(dest.signupEnabled)
            }
            null -> Unit
        }
    }

    // imePadding keeps the focused field / Connect pill reachable once the
    // viewer explicitly opens the soft IME (the manual field only raises the
    // keyboard on an explicit center-press, see ManualEntryCard).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TvAuroraBackdrop(variant = TvAuroraVariant.Server)

        if (isActivePairing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                ActivePairingPanel(
                    status = pairingStatus,
                    onCancel = pairingReceiver::cancelActiveSession,
                    onContinue = {
                        pairingAdvertiser.stop()
                        onPairedSignIn()
                    },
                    modifier = Modifier.widthIn(max = 620.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
            ) {
                BrandHeader()

                Spacer(modifier = Modifier.height(Spacing.lg))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AuroraEyebrow(text = "Step 01 — Connect")
                    Text(
                        text = "Add your server",
                        style = TvServerSetupTextStyles.Title,
                        color = Color.White,
                    )
                }

                // The chooser is centered in the remaining vertical space. The
                // cards share one height (capped so they read as cards, not
                // full-height panels) and shrink with the box when the IME
                // opens — no fixed row height, no offsets. Widths mirror tvOS
                // (600 / 84 / 600 pt → 300 / 42 / 300 dp at the Shield's 0.5x).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = Spacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .widthIn(max = 642.dp)
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .fillMaxHeight(),
                    ) {
                        PhoneSetupCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        OrDivider(
                            modifier = Modifier
                                .width(42.dp)
                                .fillMaxHeight(),
                        )
                        ManualEntryCard(
                            state = state,
                            onServerUrlChanged = viewModel::onServerUrlChanged,
                            onConnectClick = viewModel::onConnectClick,
                            focusRequester = focusRequester,
                            urlBringIntoView = urlBringIntoView,
                            connectBringIntoView = connectBringIntoView,
                            scope = scope,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PhoneSetupCard(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .auroraGlass(16.dp)
            .padding(24.dp),
    ) {
        Text(
            text = "SET UP WITH PHONE",
            style = TvServerSetupTextStyles.Pill,
            color = Color.White.copy(alpha = 0.70f),
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )

        Spacer(modifier = Modifier.weight(1f))
        SearchingBeacon(modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Looking for your phone…",
            style = TvServerSetupTextStyles.Headline,
            color = Color.White,
        )
        Text(
            text = "Open Silo on a phone on this Wi-Fi. It can set up this TV without typing the server address.",
            style = TvServerSetupTextStyles.PairingDetail,
            color = Color.White.copy(alpha = 0.72f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManualEntryCard(
    state: TvServerSetupUiState,
    onServerUrlChanged: (String) -> Unit,
    onConnectClick: () -> Unit,
    focusRequester: FocusRequester,
    urlBringIntoView: BringIntoViewRequester,
    connectBringIntoView: BringIntoViewRequester,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .auroraGlass(16.dp, emphasized = true)
            .padding(24.dp),
    ) {
        Text(
            text = "Enter it here",
            style = TvServerSetupTextStyles.Headline,
            color = Color.White,
        )

        Text(
            text = "SERVER ADDRESS",
            style = TvServerSetupTextStyles.InputLabel,
            color = Color.White.copy(alpha = 0.52f),
        )

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrlChanged,
            placeholder = {
                Text(
                    text = "media.example.com",
                    style = TvServerSetupTextStyles.FieldText,
                )
            },
            singleLine = true,
            textStyle = TvServerSetupTextStyles.FieldText,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                showKeyboardOnFocus = false,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    if (!state.isLoading && state.serverUrl.isNotBlank()) {
                        onConnectClick()
                    }
                },
            ),
            enabled = !state.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .bringIntoViewRequester(urlBringIntoView)
                .onFocusEvent { fs ->
                    if (fs.isFocused) scope.launch { urlBringIntoView.bringIntoView() }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        keyboardController?.show()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester),
            colors = tvOutlinedTextFieldColors(),
        )

        if (state.error != null) {
            Text(
                text = state.error,
                style = TvServerSetupTextStyles.Error,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .bringIntoViewRequester(connectBringIntoView)
                .onFocusEvent { fs ->
                    if (fs.hasFocus) scope.launch { connectBringIntoView.bringIntoView() }
                },
        ) {
            AuroraPrimaryButton(
                label = if (state.isLoading) "Connecting…" else "Connect",
                icon = null,
                onClick = onConnectClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            )
        }
    }
}

@Composable
private fun OrDivider(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .weight(1f)
                .background(Color.White.copy(alpha = 0.16f)),
        )
        Text(
            text = "OR",
            style = TvServerSetupTextStyles.Pill,
            color = Color.White.copy(alpha = 0.50f),
            modifier = Modifier.padding(vertical = 14.dp),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .weight(1f)
                .background(Color.White.copy(alpha = 0.16f)),
        )
    }
}

/**
 * Pulsing "searching for a phone" beacon — three gold rings expanding outward
 * behind a phone glyph. Mirrors tvOS `SearchingBeacon`: each ring scales
 * 0.6 → 1.7 while fading 0.55 → 0 over 2.4s, staggered 0.8s apart so a new
 * pulse leaves the center as the previous one dissolves.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchingBeacon(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "phoneBeacon")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(128.dp),
    ) {
        repeat(3) { index ->
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2400, easing = EaseOut),
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 800),
                ),
                label = "phoneBeaconRing$index",
            )
            val ringScale = 0.6f + (1.7f - 0.6f) * progress
            val ringAlpha = (1f - progress) * 0.55f
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .graphicsLayer {
                        scaleX = ringScale
                        scaleY = ringScale
                        alpha = ringAlpha
                    }
                    .border(2.dp, AuroraAccent, CircleShape),
            )
        }
        Icon(
            imageVector = Icons.Default.Smartphone,
            contentDescription = null,
            tint = AuroraInk,
            modifier = Modifier.size(44.dp),
        )
    }
}

private val PairingReceiverStatus.isActivePairing: Boolean
    get() = this is PairingReceiverStatus.Connected ||
        this is PairingReceiverStatus.Pairing ||
        this is PairingReceiverStatus.AwaitingApproval ||
        this is PairingReceiverStatus.SignedIn ||
        this is PairingReceiverStatus.Completed ||
        this is PairingReceiverStatus.Failed

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActivePairingPanel(
    status: PairingReceiverStatus,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .auroraGlass(18.dp)
            .padding(horizontal = 52.dp, vertical = 42.dp),
    ) {
        when (status) {
            PairingReceiverStatus.Connected -> {
                AuroraEyebrow(text = "Step 01 — Connect")
                Text(
                    text = "Phone connected",
                    style = TvServerSetupTextStyles.PairingTitle,
                    color = Color.White,
                )
                WaitingDots()
                Text(
                    text = "Choose which server to set up on your phone.",
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = Color.White.copy(alpha = 0.72f),
                )
                AuroraGhostButton(label = "Cancel", onClick = onCancel)
            }
            is PairingReceiverStatus.Pairing -> {
                AuroraEyebrow(text = "Almost there")
                Text(
                    text = "Setting up this TV",
                    style = TvServerSetupTextStyles.PairingTitle,
                    color = Color.White,
                )
                ServerNameLabel(status.serverName)
                WaitingDots(compact = true)
                Text(
                    text = "Starting sign-in on your phone.",
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = Color.White.copy(alpha = 0.72f),
                )
                AuroraGhostButton(label = "Cancel", onClick = onCancel)
            }
            is PairingReceiverStatus.AwaitingApproval -> {
                AuroraEyebrow(text = "Almost there")
                Text(
                    text = "Confirm on your phone",
                    style = TvServerSetupTextStyles.PairingTitle,
                    color = Color.White,
                )
                MatchCodeCard(code = status.matchCode)
                ServerNameLabel(status.serverName)
                WaitingDots(compact = true)
                Text(
                    text = "Make sure your phone shows this same code before approving sign-in.",
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = Color.White.copy(alpha = 0.72f),
                )
                AuroraGhostButton(label = "Cancel", onClick = onCancel)
            }
            is PairingReceiverStatus.SignedIn -> {
                AuroraEyebrow(text = "All set")
                SuccessMark()
                Text(
                    text = if (status.serverCount <= 1) "Signed in" else "Signed in to ${status.serverCount} servers",
                    style = TvServerSetupTextStyles.PairingTitle,
                    color = Color.White,
                )
                WaitingDots(compact = true)
                Text(
                    text = "Finishing up on your phone…",
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = Color.White.copy(alpha = 0.72f),
                )
                AuroraGhostButton(label = "Cancel", onClick = onCancel)
            }
            is PairingReceiverStatus.Completed -> {
                AuroraEyebrow(text = "All set")
                SuccessMark()
                Text(
                    text = "You’re all set",
                    style = TvServerSetupTextStyles.PairingTitle,
                    color = Color.White,
                )
                Text(
                    text = completedSummary(status.serverNames),
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = Color.White.copy(alpha = 0.72f),
                )
                AuroraPrimaryButton(
                    label = "Continue",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    onClick = onContinue,
                    modifier = Modifier.width(320.dp),
                )
            }
            is PairingReceiverStatus.Failed -> {
                AuroraEyebrow(text = "Step 01 — Connect")
                Text(
                    text = "Setup didn’t finish",
                    style = TvServerSetupTextStyles.PairingTitle,
                    color = Color.White,
                )
                Text(
                    text = "Something went wrong setting up ${status.serverName}. Try again from your phone, or set up your server manually.",
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = MaterialTheme.colorScheme.error,
                )
                AuroraPrimaryButton(
                    label = "Try again",
                    onClick = onCancel,
                    modifier = Modifier.width(320.dp),
                )
            }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerNameLabel(serverName: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "SETTING UP",
            style = TvServerSetupTextStyles.CodeLabel,
            color = Color.White.copy(alpha = 0.48f),
        )
        Text(
            text = serverName,
            style = TvServerSetupTextStyles.Headline,
            color = Color.White,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WaitingDots(compact: Boolean = false) {
    val dotSize = if (compact) 8.dp else 12.dp
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(999.dp)),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SuccessMark() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(74.dp)
            .background(Color(0xFF22C55E).copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .border(2.dp, Color(0xFF22C55E).copy(alpha = 0.68f), RoundedCornerShape(999.dp)),
    ) {
        Text(
            text = "✓",
            style = TvServerSetupTextStyles.SuccessMark,
            color = Color(0xFF86EFAC),
        )
    }
}

private fun completedSummary(names: List<String>): String =
    when (names.size) {
        0 -> "Taking you to your profiles…"
        1 -> "Signed in to ${names[0]}. Taking you to your profiles…"
        else -> "Signed in to ${names.joinToString(", ")}. Taking you to your profiles…"
    }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MatchCodeCard(code: String) {
    if (code.isBlank()) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .auroraGlass(12.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        Text(
            text = "CONFIRM THIS CODE",
            style = TvServerSetupTextStyles.CodeLabel,
            color = Color.White.copy(alpha = 0.62f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            code.uppercase().forEach { ch ->
                if (ch == '-' || ch == ' ') {
                    Text(
                        text = "–",
                        style = TvServerSetupTextStyles.CodeSeparator,
                        color = Color.White.copy(alpha = 0.42f),
                        modifier = Modifier.width(12.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = 34.dp, height = 42.dp)
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ch.toString(),
                            style = TvServerSetupTextStyles.CodeDigit,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private object TvServerSetupTextStyles {
    val Title = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    )

    val FieldText = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        color = Color.White,
    )

    val Headline = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    )

    val InputLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 3.sp,
    )

    val Pill = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 2.sp,
    )

    val Error = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )

    val PairingDetail = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    )

    val PairingTitle = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    )

    val CodeLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 2.sp,
    )

    val CodeDigit = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    )

    val CodeSeparator = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    )

    val SuccessMark = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp,
    )
}

/** Compact horizontal brand row — replaces the oversized centered logo/title pair. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrandHeader(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier,
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
