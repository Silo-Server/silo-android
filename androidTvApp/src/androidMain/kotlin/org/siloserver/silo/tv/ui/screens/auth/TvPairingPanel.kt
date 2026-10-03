package org.siloserver.silo.tv.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.pairing.PairingReceiverStatus
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.pairing.PairingFailureCode
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.AuroraEyebrow
import org.siloserver.silo.tv.ui.components.AuroraGhostButton
import org.siloserver.silo.tv.ui.components.AuroraPrimaryButton
import org.siloserver.silo.tv.ui.components.auroraGlass
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.Spacing

/** Whether the receiver is in a phone session the TV should show in place of its own screen. */
internal val PairingReceiverStatus.isActivePairing: Boolean
    get() = this is PairingReceiverStatus.Connected ||
        this is PairingReceiverStatus.ConsentRequested ||
        this is PairingReceiverStatus.Pairing ||
        this is PairingReceiverStatus.Unreachable ||
        this is PairingReceiverStatus.AwaitingApproval ||
        this is PairingReceiverStatus.SignedIn ||
        this is PairingReceiverStatus.Completed ||
        this is PairingReceiverStatus.Failed

/**
 * The nearby-phone flow on the TV, shown in place of server setup or the
 * sign-in screen while a phone or tablet is connected. [signIn] is true on the
 * sign-in screen (`st=login`): the TV already has its server and only the
 * account is being signed in. People compare the TV's sign-in code; the match
 * words are never shown.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ActivePairingPanel(
    status: PairingReceiverStatus,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onUseAlternate: () -> Unit,
    onRetryAddress: () -> Unit,
    signIn: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val primaryFocus = remember { FocusRequester() }
    var panelHasFocus by remember { mutableStateOf(false) }
    // Every state with a decision puts focus on it; a prompt whose button
    // never takes focus can't be answered from a remote at all.
    val focusKey = when (status) {
        is PairingReceiverStatus.ConsentRequested -> "consent"
        is PairingReceiverStatus.Unreachable -> "unreachable"
        is PairingReceiverStatus.Failed -> "failed"
        is PairingReceiverStatus.Completed -> "completed"
        else -> null
    }
    LaunchedEffect(focusKey) {
        if (focusKey != null) {
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = primaryFocus::requestFocus,
                isFocused = { panelHasFocus },
            )
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .onFocusChanged { panelHasFocus = it.hasFocus }
            .fillMaxWidth(),
    ) {
        when (status) {
            PairingReceiverStatus.Connected -> {
                AuroraEyebrow(text = stringResource(if (signIn) R.string.tv_pairing_eyebrow_signin else R.string.tv_pairing_eyebrow_connect))
                PanelTitle(stringResource(R.string.tv_pairing_connected_title))
                WaitingDots()
                PanelDetail(
                    stringResource(
                        if (signIn) R.string.tv_pairing_connected_detail_signin else R.string.tv_pairing_connected_detail,
                    ),
                )
                AuroraGhostButton(label = stringResource(R.string.tv_pairing_cancel), onClick = onCancel)
            }
            is PairingReceiverStatus.ConsentRequested -> {
                AuroraEyebrow(text = stringResource(if (signIn) R.string.tv_pairing_eyebrow_signin else R.string.tv_pairing_eyebrow_connect))
                PanelTitle(
                    stringResource(if (signIn) R.string.tv_pairing_consent_title_signin else R.string.tv_pairing_consent_title),
                )
                PanelDetail(stringResource(R.string.tv_pairing_consent_detail, status.serverName))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(top = Spacing.sm),
                ) {
                    AuroraPrimaryButton(
                        label = stringResource(R.string.tv_pairing_allow),
                        onClick = onAllow,
                        focusRequester = primaryFocus,
                        modifier = Modifier
                            .width(180.dp)
                            .height(60.dp),
                    )
                    AuroraGhostButton(
                        label = stringResource(R.string.tv_pairing_dont_allow),
                        onClick = onDeny,
                        modifier = Modifier
                            .width(180.dp)
                            .height(60.dp),
                    )
                }
            }
            is PairingReceiverStatus.Pairing -> {
                AuroraEyebrow(text = stringResource(R.string.tv_pairing_eyebrow_almost))
                PanelTitle(
                    stringResource(if (signIn) R.string.tv_pairing_starting_title_signin else R.string.tv_pairing_starting_title),
                )
                ServerNameLabel(status.serverName, signIn)
                WaitingDots(compact = true)
                PanelDetail(stringResource(R.string.tv_pairing_starting_detail))
                AuroraGhostButton(label = stringResource(R.string.tv_pairing_cancel), onClick = onCancel)
            }
            is PairingReceiverStatus.Unreachable -> {
                AuroraEyebrow(text = stringResource(R.string.tv_pairing_eyebrow_almost))
                PanelTitle(stringResource(R.string.tv_pairing_unreachable_title, status.serverName))
                val providerName = status.providerName
                PanelDetail(
                    if (providerName != null) {
                        stringResource(R.string.tv_pairing_unreachable_provider, status.serverName, providerName)
                    } else {
                        stringResource(
                            R.string.tv_pairing_unreachable_public,
                            status.serverName,
                            DeviceCodeFormat.host(status.serverURL),
                        )
                    },
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    val alternate = status.alternateUrl
                    if (alternate != null) {
                        AuroraPrimaryButton(
                            label = stringResource(R.string.tv_pairing_use_alternate, DeviceCodeFormat.host(alternate)),
                            onClick = onUseAlternate,
                            focusRequester = primaryFocus,
                            modifier = Modifier.width(320.dp),
                        )
                        AuroraGhostButton(
                            label = stringResource(R.string.tv_pairing_try_again),
                            onClick = onRetryAddress,
                            modifier = Modifier.width(320.dp),
                        )
                    } else {
                        AuroraPrimaryButton(
                            label = stringResource(R.string.tv_pairing_try_again),
                            onClick = onRetryAddress,
                            focusRequester = primaryFocus,
                            modifier = Modifier.width(320.dp),
                        )
                    }
                    AuroraGhostButton(
                        label = stringResource(R.string.tv_pairing_cancel),
                        onClick = onCancel,
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
            is PairingReceiverStatus.AwaitingApproval -> {
                AuroraEyebrow(text = stringResource(R.string.tv_pairing_eyebrow_almost))
                PanelTitle(
                    stringResource(
                        if (status.automatic) R.string.tv_pairing_confirm_title_automatic else R.string.tv_pairing_confirm_title,
                    ),
                )
                SignInCodeCard(userCode = status.userCode)
                olderPhonesMatchWords(status)?.let { words ->
                    Text(
                        text = stringResource(R.string.tv_pairing_confirm_older_phones, words),
                        style = TvPairingTextStyles.Caption,
                        color = Color.White.copy(alpha = 0.48f),
                        textAlign = TextAlign.Center,
                    )
                }
                ServerNameLabel(status.serverName, signIn)
                WaitingDots(compact = true)
                PanelDetail(
                    stringResource(
                        if (status.automatic) R.string.tv_pairing_confirm_detail_automatic else R.string.tv_pairing_confirm_detail,
                    ),
                )
                AuroraGhostButton(label = stringResource(R.string.tv_pairing_cancel), onClick = onCancel)
            }
            is PairingReceiverStatus.SignedIn -> {
                AuroraEyebrow(text = stringResource(R.string.tv_pairing_eyebrow_done))
                SuccessMark()
                PanelTitle(
                    if (status.serverCount <= 1) {
                        stringResource(R.string.tv_pairing_signed_in)
                    } else {
                        stringResource(R.string.tv_pairing_signed_in_count, status.serverCount)
                    },
                )
                WaitingDots(compact = true)
                PanelDetail(stringResource(R.string.tv_pairing_finishing))
            }
            is PairingReceiverStatus.Completed -> {
                AuroraEyebrow(text = stringResource(R.string.tv_pairing_eyebrow_done))
                SuccessMark()
                PanelTitle(stringResource(R.string.tv_pairing_completed_title))
                PanelDetail(
                    if (status.serverNames.isEmpty()) {
                        stringResource(R.string.tv_pairing_completed_none)
                    } else {
                        stringResource(R.string.tv_pairing_completed_named, status.serverNames.joinToString(", "))
                    },
                )
                AuroraPrimaryButton(
                    label = stringResource(R.string.tv_pairing_continue),
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    onClick = onContinue,
                    focusRequester = primaryFocus,
                    modifier = Modifier.width(320.dp),
                )
            }
            is PairingReceiverStatus.Failed -> {
                AuroraEyebrow(text = stringResource(if (signIn) R.string.tv_pairing_eyebrow_signin else R.string.tv_pairing_eyebrow_connect))
                PanelTitle(
                    stringResource(if (signIn) R.string.tv_pairing_failed_title_signin else R.string.tv_pairing_failed_title),
                )
                Text(
                    text = stringResource(status.code.messageRes(), status.serverName),
                    style = TvPairingTextStyles.Detail,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                AuroraPrimaryButton(
                    label = stringResource(R.string.tv_pairing_try_again),
                    onClick = onCancel,
                    focusRequester = primaryFocus,
                    modifier = Modifier.width(320.dp),
                )
            }
            else -> Unit
        }
    }
}

/**
 * The match words for the "Older phones show ..." line: only when a person
 * compares codes (not an automatic attempt) and the words are known, in
 * capitals like the phones show them. Remove with that line.
 */
internal fun olderPhonesMatchWords(status: PairingReceiverStatus.AwaitingApproval): String? =
    status.matchCode?.trim()?.takeIf { !status.automatic && it.isNotEmpty() }?.uppercase()

private fun PairingFailureCode.messageRes(): Int = when (this) {
    PairingFailureCode.Denied -> R.string.tv_pairing_failed_denied
    PairingFailureCode.Expired -> R.string.tv_pairing_failed_expired
    PairingFailureCode.Unreachable -> R.string.tv_pairing_failed_unreachable
    PairingFailureCode.IdentityMismatch -> R.string.tv_pairing_failed_identity
    PairingFailureCode.UpdateRequired -> R.string.tv_pairing_failed_update
    PairingFailureCode.AuthFailed -> R.string.tv_pairing_failed_generic
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelTitle(text: String) {
    Text(
        text = text,
        style = TvPairingTextStyles.Title,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelDetail(text: String) {
    Text(
        text = text,
        style = TvPairingTextStyles.Detail,
        color = Color.White.copy(alpha = 0.72f),
        textAlign = TextAlign.Center,
    )
}

/**
 * The TV's sign-in code, grouped 4+4, the same code the phone asks the person
 * to check. Read to TalkBack character by character.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SignInCodeCard(userCode: String) {
    if (userCode.isBlank()) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .auroraGlass(12.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        SignInCodeText(
            code = DeviceCodeFormat.display(userCode),
            spokenCode = DeviceCodeFormat.spoken(userCode),
            label = stringResource(R.string.tv_signin_code_label),
            style = TvSignInCodeStyle,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerNameLabel(serverName: String, signIn: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = stringResource(if (signIn) R.string.tv_pairing_server_label_signin else R.string.tv_pairing_server_label),
            style = TvPairingTextStyles.Label,
            color = Color.White.copy(alpha = 0.48f),
        )
        Text(
            text = serverName,
            style = TvPairingTextStyles.Headline,
            color = Color.White,
        )
    }
}

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
            style = TvPairingTextStyles.SuccessMark,
            color = Color(0xFF86EFAC),
        )
    }
}

private object TvPairingTextStyles {
    val Title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp)
    val Detail = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val Caption = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val Headline = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val Label = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 2.sp,
    )
    val SuccessMark = TextStyle(fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 38.sp)
}
