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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import org.siloserver.silo.common.pairing.PairingAdvertisement
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
import org.siloserver.silo.tv.ui.components.tvShowImeOnSelect
import org.siloserver.silo.tv.ui.components.TvAuthFormDefaults
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFocusLog
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
    val phoneSetupFocus = remember { FocusRequester() }
    var phoneCardHasFocus by remember { mutableStateOf(false) }
    val formScrollState = rememberTvImeAwareFormScrollState()
    val isActivePairing = pairingStatus.isActivePairing

    // Companion LAN pairing: advertise `_silopair._tcp` while this screen is on
    // so a phone running Silo can push the server URL + drive device-login,
    // sparing the viewer from typing a URL on the remote. Advertising stops
    // when the screen leaves the composition.
    var advertiserToken by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        val token = pairingAdvertiser.start(PairingAdvertisement.Setup)
        advertiserToken = token
        onDispose { pairingAdvertiser.stop(token) }
    }
    // Snapshot-backed: re-keys the claim when the viewer switches between
    // pointer and key input.
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(isActivePairing, inputMode) {
        // Pointer users click what they want — and in touch mode the claim
        // could not land anyway. Re-run on mode flip so the D-pad always has
        // somewhere to start.
        if (!isActivePairing && inputMode != InputMode.Touch) {
            // Land on the phone-pairing card: companion setup is the
            // recommended path, so it gets first focus (product call
            // 2026-08-14, reversing the 2026-07-10 field-first default).
            // Landing on the URL field also popped the IME over the form,
            // and the IME resize scrolled the header chrome off-screen.
            TvFocusLog.d { "serverSetup: claiming phone card (mode=$inputMode)" }
            val result = requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = phoneSetupFocus::requestFocus,
                isFocused = { phoneCardHasFocus },
            )
            TvFocusLog.d { "serverSetup: claim result=$result" }
        } else {
            TvFocusLog.d {
                "serverSetup: claim skipped (pairing=$isActivePairing, mode=$inputMode)"
            }
        }
    }
    LaunchedEffect(pairingStatus) {
        if (pairingStatus is PairingReceiverStatus.Completed) {
            delay(1_800)
            pairingAdvertiser.stop(advertiserToken)
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
                        pairingAdvertiser.stop(advertiserToken)
                        onPairedSignIn()
                    },
                    onAllow = pairingReceiver::allowPendingServer,
                    onDeny = pairingReceiver::denyPendingServer,
                    onUseAlternate = pairingReceiver::useAlternateAddress,
                    onRetryAddress = pairingReceiver::retryPushedAddress,
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
                        modifier = Modifier.width(215.dp),
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
                        text = stringResource(R.string.tv_setup_subtitle),
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
                            // Intrinsic-min, floored — not a bare heightIn and
                            // not an exact height. Both of those fail, in
                            // opposite directions:
                            //  - a loose max makes the cards' fillMaxHeight a
                            //    no-op, so the phone card collapses to its pill
                            //    and the weight(1f) beacon box measures zero;
                            //  - an exact height clips the taller card, which
                            //    at 300dp squeezed "Connect to server" down to
                            //    a blank pill (label measured 6px in a 96px
                            //    button).
                            // Resolving the intrinsic first hands the Row a
                            // tight height, so fillMaxHeight still resolves,
                            // while the floor keeps the chooser at a real card
                            // height when content is short. tvOS pins 580pt;
                            // here the content decides above that floor.
                            .height(IntrinsicSize.Min)
                            .heightIn(min = SERVER_SETUP_CHOOSER_MIN_HEIGHT),
                    ) {
                        PhoneSetupCard(
                            focusRequester = phoneSetupFocus,
                            modifier = Modifier
                                .onFocusChanged { phoneCardHasFocus = it.hasFocus }
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
        // Top-leading pill, matching tvOS TVServerSetupView.phoneCard.
        Text(
            text = stringResource(R.string.tv_setup_phone_pill),
            style = TvServerSetupTextStyles.Pill,
            color = Color.White.copy(alpha = 0.70f),
            modifier = Modifier
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
    // Beacon centered, copy left-aligned beneath it — mirrors tvOS
    // TVServerSetupView.phoneCard, with its neutral "phone or tablet" copy.
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        SearchingBeacon(
            modifier = Modifier
                .size(PHONE_SETUP_BEACON_SIZE)
                .align(Alignment.CenterHorizontally),
        )

        Text(
            text = stringResource(R.string.tv_setup_phone_looking),
            style = TvServerSetupTextStyles.Headline,
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.tv_setup_phone_detail),
            style = TvServerSetupTextStyles.PairingDetail,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val PHONE_SETUP_BEACON_SIZE = 96.dp

/**
 * Floor for the phone/manual chooser. The manual card's intrinsic height
 * normally exceeds this; the floor only matters when it doesn't, keeping the
 * phone card from collapsing to its pill.
 */
private val SERVER_SETUP_CHOOSER_MIN_HEIGHT = 300.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManualEntryCard(
    state: TvServerSetupUiState,
    onServerUrlChanged: (String) -> Unit,
    onConnectClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
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
                text = "Enter the server address",
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
                        text = "silo.example.com",
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
                    .height(TvAuthFormDefaults.FieldHeight)
                    .tvShowImeOnSelect()
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
        } else {
            // Mirrors tvOS's lock.shield reassurance line. Truthful here too:
            // bare hosts probe https:// first and fall to http:// only when
            // the viewer typed it (probeTvServerSetupCandidates).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Secure HTTPS is tried automatically.",
                    style = TvServerSetupTextStyles.PairingDetail,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }

        Box {
            AuroraPrimaryButton(
                label = if (state.isLoading) "Connecting…" else "Connect to server",
                icon = null,
                enabled = canSubmitTvServerUrl(state.serverUrl, state.isLoading),
                onClick = {
                    if (canSubmitTvServerUrl(state.serverUrl, state.isLoading)) {
                        onConnectClick()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TvAuthFormDefaults.PrimaryButtonHeight),
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
        // Hairlines fade toward the screen edges, matching tvOS orDivider.
        Box(
            modifier = Modifier
                .width(1.dp)
                .weight(1f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.16f)),
                    ),
                ),
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
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    ),
                ),
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

    /** tvOS continuumHeadline (36pt → 18dp at the 0.5x map, +2 readability). */
    val Headline = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
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
