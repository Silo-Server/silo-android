package org.siloserver.silo.tv.ui.screens.auth

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.pairing.PairingReceiver
import org.siloserver.silo.common.pairing.PairingReceiverStatus
import org.siloserver.silo.common.pairing.TvPairingAdvertiser
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.AuroraAccent
import org.siloserver.silo.tv.ui.components.AuroraEyebrow
import org.siloserver.silo.tv.ui.components.AuroraGhostButton
import org.siloserver.silo.tv.ui.components.AuroraInk
import org.siloserver.silo.tv.ui.components.AuroraJourneyProgress
import org.siloserver.silo.tv.ui.components.AuroraPrimaryButton
import org.siloserver.silo.tv.ui.components.TvAuroraBackdrop
import org.siloserver.silo.tv.ui.components.TvAuroraVariant
import org.siloserver.silo.tv.ui.components.TvHideStockImeOnDispose
import org.siloserver.silo.tv.ui.components.auroraGlass
import org.siloserver.silo.tv.ui.components.rememberTvImeAwareFormScrollState
import org.siloserver.silo.tv.ui.components.tvImeAwareFieldContext
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.Spacing

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
    var hostFieldHasFocus by remember { mutableStateOf(false) }
    val phoneSetupFocus = remember { FocusRequester() }
    val formScrollState = rememberTvImeAwareFormScrollState()
    val isActivePairing = pairingStatus.isActivePairing

    // Companion LAN pairing: advertise `_silopair._tcp` while this screen is on
    // so a phone running Silo can push the server URL + drive device-login,
    // sparing the viewer from typing a URL on the remote. Advertising stops
    // when the screen leaves the composition.
    DisposableEffect(Unit) {
        pairingAdvertiser.start()
        onDispose { pairingAdvertiser.stop() }
    }
    LaunchedEffect(isActivePairing) {
        if (!isActivePairing) {
            // Always land on the server-address field, matching tvOS
            // (TVServerSetupView `.defaultFocus(.host)`). The user chooses "Set
            // up with phone" by navigating to it — we don't pre-select it for
            // them (Jim TV QA 2026-07-10). Returning users keep the pre-filled
            // field focused too.
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = focusRequester::requestFocus,
                isFocused = { hostFieldHasFocus },
            )
        }
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

    state.pendingCleartextUrl?.let { origin ->
        AlertDialog(
            onDismissRequest = viewModel::cancelCleartextConnection,
            title = { Text("Use unencrypted HTTP?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(origin, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This connection is not encrypted. Anyone on the network may see or change " +
                            "traffic, including your sign-in. Continue only on a network you trust.",
                    )
                }
            },
            confirmButton = {
                AuroraPrimaryButton(
                    label = "Use HTTP",
                    onClick = viewModel::confirmCleartextConnection,
                    modifier = Modifier.width(180.dp),
                    enabled = !state.isLoading,
                )
            },
            dismissButton = {
                AuroraGhostButton(
                    label = "Cancel",
                    onClick = viewModel::cancelCleartextConnection,
                )
            },
        )
    }

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
                    onAllow = pairingReceiver::allowPendingServer,
                    onDeny = pairingReceiver::denyPendingServer,
                    // tvOS renders pairing states directly on the Aurora backdrop
                    // in an 880pt content column. Shield uses a 2x density, so
                    // 440dp produces the same 880px maximum footprint.
                    modifier = Modifier.widthIn(max = 440.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
                    .verticalScroll(formScrollState)
                    .padding(horizontal = 48.dp, vertical = 32.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandHeader()
                    AuroraJourneyProgress(
                        currentStep = 1,
                        modifier = Modifier.width(230.dp),
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.lg))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AuroraEyebrow(text = "Connect")
                    Text(
                        text = "Connect this Android TV",
                        style = TvServerSetupTextStyles.Title,
                        color = Color.White,
                    )
                    Text(
                        text = "Use your phone, or enter the server address with the remote.",
                        style = TvServerSetupTextStyles.PairingDetail,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center,
                    )
                }

                // Keep the chooser at a real card height even while the
                // platform IME resizes the activity. Without the scrollable,
                // top-anchored container, Android TV can squeeze the field's
                // editable text line to a few pixels and make typed input
                // appear blank.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .widthIn(max = 642.dp)
                            .fillMaxWidth()
                            .heightIn(min = SERVER_SETUP_CHOOSER_HEIGHT),
                    ) {
                        PhoneSetupCard(
                            focusRequester = phoneSetupFocus,
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
                            modifier = Modifier
                                .onFocusChanged { hostFieldHasFocus = it.hasFocus }
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
private fun PhoneSetupCard(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    // Focusable so FIRST sign-in can land here instead of committing the user
    // to the manual URL path — the card itself needs no click action (the TV
    // is already advertising; the phone drives the rest).
    var focused by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .auroraGlass(16.dp)
            .border(
                width = 2.dp,
                color = if (focused) Color.White.copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(24.dp),
    ) {
        Text(
            text = "RECOMMENDED · USE PHONE",
            style = TvServerSetupTextStyles.Pill,
            color = Color.White.copy(alpha = 0.70f),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            PhoneSetupBody()
        }
    }
}

@Composable
private fun PhoneSetupBody(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        SearchingBeacon(
            modifier = Modifier.size(PHONE_SETUP_BEACON_SIZE),
        )

        Text(
            text = "Looking for your phone…",
            style = TvServerSetupTextStyles.Headline,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Open Silo on your phone on this Wi-Fi to set up this TV without typing.",
            style = TvServerSetupTextStyles.PairingDetail,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val PHONE_SETUP_BEACON_SIZE = 96.dp
private val SERVER_SETUP_CHOOSER_HEIGHT = 300.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManualEntryCard(
    state: TvServerSetupUiState,
    onServerUrlChanged: (String) -> Unit,
    onConnectClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    TvHideStockImeOnDispose()
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .auroraGlass(16.dp, emphasized = true)
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .tvImeAwareFieldContext(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                        if (canSubmitTvServerUrl(state.serverUrl, state.isLoading)) {
                            onConnectClick()
                        }
                    },
                ),
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
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
        }

        UrlShortcutRow(
            enabled = !state.isLoading,
            onShortcut = { shortcut ->
                onServerUrlChanged(applyServerUrlShortcut(state.serverUrl, shortcut))
            },
        )

        if (state.error != null) {
            Text(
                text = state.error,
                style = TvServerSetupTextStyles.Error,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (state.usesCleartext) {
            Text(
                text = "This server uses unencrypted HTTP. Fine on a trusted home network; " +
                    "avoid it on public Wi-Fi.",
                style = TvServerSetupTextStyles.Error,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            AuroraPrimaryButton(
                label = if (state.isLoading) "Connecting…" else "Connect",
                icon = null,
                enabled = canSubmitTvServerUrl(state.serverUrl, state.isLoading),
                onClick = {
                    if (canSubmitTvServerUrl(state.serverUrl, state.isLoading)) {
                        onConnectClick()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            )
        }
    }
}

@Composable
private fun UrlShortcutRow(
    enabled: Boolean,
    onShortcut: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        ServerUrlShortcuts.forEach { shortcut ->
            AuroraGhostButton(
                label = shortcut,
                onClick = { if (enabled) onShortcut(shortcut) },
                fontSize = 16.sp,
                horizontalPadding = 12.dp,
                verticalPadding = 6.dp,
            )
        }
    }
}

private val ServerUrlShortcuts = listOf("https://", "http://", ".com")

private fun applyServerUrlShortcut(value: String, shortcut: String): String =
    when {
        shortcut.endsWith("://") &&
            (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) -> value
        shortcut.endsWith("://") -> shortcut + value.trimStart()
        else -> value + shortcut
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
 * behind a phone glyph. Mirrors tvOS `SearchingBeacon`, scaled by the caller.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchingBeacon(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "phoneBeacon")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
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
                    .size(54.dp)
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
            modifier = Modifier.size(36.dp),
        )
    }
}

private val PairingReceiverStatus.isActivePairing: Boolean
    get() = this is PairingReceiverStatus.Connected ||
        this is PairingReceiverStatus.ConsentRequested ||
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
    onAllow: () -> Unit = {},
    onDeny: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val allowFocusRequester = remember { FocusRequester() }
    var consentHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (status is PairingReceiverStatus.ConsentRequested) {
            // A consent prompt whose Allow button never takes focus cannot be
            // answered from a remote at all.
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = allowFocusRequester::requestFocus,
                isFocused = { consentHasFocus },
            )
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .onFocusChanged { consentHasFocus = it.hasFocus }
            .fillMaxWidth(),
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
                    text = "On your phone, choose which servers this TV should sign in to.",
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = Color.White.copy(alpha = 0.72f),
                )
                AuroraGhostButton(label = "Cancel", onClick = onCancel)
            }
            is PairingReceiverStatus.ConsentRequested -> {
                AuroraEyebrow(text = "Step 01 — Connect")
                Text(
                    text = "Allow this setup?",
                    style = TvServerSetupTextStyles.PairingTitle,
                    color = Color.White,
                )
                Text(
                    text = "A nearby phone wants to sign this TV in to ${status.serverName}.",
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = Color.White.copy(alpha = 0.72f),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(top = Spacing.sm),
                ) {
                    AuroraPrimaryButton(
                        label = "Allow",
                        onClick = onAllow,
                        focusRequester = allowFocusRequester,
                        modifier = Modifier
                            .width(180.dp)
                            .height(60.dp),
                    )
                    AuroraGhostButton(
                        label = "Don\u2019t allow",
                        onClick = onDeny,
                        modifier = Modifier
                            .width(180.dp)
                            .height(60.dp),
                    )
                }
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
                    text = "Starting secure sign-in…",
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
    val tileWidthDp = matchCodeTileWidthDp(code)
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
        Row(horizontalArrangement = Arrangement.spacedBy(MATCH_CODE_TILE_GAP_DP.dp)) {
            code.uppercase().forEach { ch ->
                if (ch == '-' || ch == ' ') {
                    Text(
                        text = "–",
                        style = TvServerSetupTextStyles.CodeSeparator,
                        color = Color.White.copy(alpha = 0.42f),
                        modifier = Modifier.width(MATCH_CODE_SEPARATOR_WIDTH_DP.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = tileWidthDp.dp, height = 42.dp)
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
        fontSize = 16.sp,
        lineHeight = 19.sp,
        letterSpacing = 3.sp,
    )

    val Pill = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 19.sp,
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
        fontSize = 16.sp,
        lineHeight = 22.sp,
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
        fontSize = 16.sp,
        lineHeight = 20.sp,
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
