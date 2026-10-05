package org.siloserver.silo.tv.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import org.siloserver.silo.common.pairing.PairingReceiverStatus
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.pairing.PairingFailureCode
import org.siloserver.silo.tv.R
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.rememberReduceMotion
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeBody
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButton
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButtonKind
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeCard
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeCardSymbol
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeCodeTiles
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeHeadline
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeScreen
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeStatusChip
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import kotlinx.coroutines.delay
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved

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
    // never takes focus can't be answered from a remote at all. Waiting
    // states don't, so a stray Select can't cancel the phone's session.
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
    val eyebrow = when (status) {
        is PairingReceiverStatus.SignedIn, is PairingReceiverStatus.Completed -> R.string.tv_pairing_eyebrow_done
        is PairingReceiverStatus.Pairing, is PairingReceiverStatus.Unreachable, is PairingReceiverStatus.AwaitingApproval ->
            R.string.tv_pairing_eyebrow_almost
        else -> if (signIn) R.string.tv_pairing_eyebrow_signin else R.string.tv_pairing_eyebrow_connect
    }
    TvMarqueeScreen(
        modifier = modifier.onFocusChanged { panelHasFocus = it.hasFocus },
        copy = {
            TvMarqueeStatusChip(stringResource(eyebrow), icon = Icons.Outlined.PhoneAndroid, modifier = Modifier.padding(bottom = 18.dp))
            when (status) {
                PairingReceiverStatus.Connected -> {
                    PanelTitle(stringResource(R.string.tv_pairing_connected_title))
                    PanelDetail(
                        stringResource(
                            if (signIn) R.string.tv_pairing_connected_detail_signin else R.string.tv_pairing_connected_detail,
                        ),
                    )
                    PanelActions { CancelButton(onCancel, primaryFocus) }
                }
                is PairingReceiverStatus.ConsentRequested -> {
                    PanelTitle(
                        stringResource(if (signIn) R.string.tv_pairing_consent_title_signin else R.string.tv_pairing_consent_title),
                    )
                    PanelDetail(stringResource(R.string.tv_pairing_consent_detail, status.serverName))
                    PanelActions {
                        TvMarqueeButton(text = stringResource(R.string.tv_pairing_allow), onClick = onAllow, focusRequester = primaryFocus)
                        TvMarqueeButton(text = stringResource(R.string.tv_pairing_dont_allow), onClick = onDeny, kind = TvMarqueeButtonKind.Plain)
                    }
                }
                is PairingReceiverStatus.Pairing -> {
                    PanelTitle(
                        stringResource(if (signIn) R.string.tv_pairing_starting_title_signin else R.string.tv_pairing_starting_title),
                    )
                    PanelDetail(stringResource(R.string.tv_pairing_starting_detail))
                    PanelActions { CancelButton(onCancel, primaryFocus) }
                }
                is PairingReceiverStatus.Unreachable -> {
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
                    PanelActions {
                        val alternate = status.alternateUrl
                        if (alternate != null) {
                            TvMarqueeButton(
                                text = stringResource(R.string.tv_pairing_use_alternate, DeviceCodeFormat.host(alternate)),
                                onClick = onUseAlternate,
                                focusRequester = primaryFocus,
                            )
                            TvMarqueeButton(
                                text = stringResource(R.string.tv_pairing_try_again),
                                onClick = onRetryAddress,
                                kind = TvMarqueeButtonKind.Glass,
                            )
                        } else {
                            TvMarqueeButton(
                                text = stringResource(R.string.tv_pairing_try_again),
                                onClick = onRetryAddress,
                                focusRequester = primaryFocus,
                            )
                        }
                        CancelButton(onCancel)
                    }
                }
                is PairingReceiverStatus.AwaitingApproval -> {
                    PanelTitle(
                        stringResource(
                            if (status.automatic) R.string.tv_pairing_confirm_title_automatic else R.string.tv_pairing_confirm_title,
                        ),
                    )
                    PanelDetail(
                        stringResource(
                            if (status.automatic) R.string.tv_pairing_confirm_detail_automatic else R.string.tv_pairing_confirm_detail,
                        ),
                    )
                    PanelActions { CancelButton(onCancel, primaryFocus) }
                }
                is PairingReceiverStatus.SignedIn -> {
                    PanelTitle(
                        if (status.serverCount <= 1) {
                            stringResource(R.string.tv_pairing_signed_in)
                        } else {
                            stringResource(R.string.tv_pairing_signed_in_count, status.serverCount)
                        },
                    )
                    PanelDetail(stringResource(R.string.tv_pairing_finishing))
                }
                is PairingReceiverStatus.Completed -> {
                    PanelTitle(stringResource(R.string.tv_pairing_completed_title))
                    PanelDetail(
                        if (status.serverNames.isEmpty()) {
                            stringResource(R.string.tv_pairing_completed_none)
                        } else {
                            stringResource(R.string.tv_pairing_completed_named, status.serverNames.joinToString(", "))
                        },
                    )
                    PanelActions {
                        TvMarqueeButton(
                            text = stringResource(R.string.tv_pairing_continue),
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            onClick = onContinue,
                            focusRequester = primaryFocus,
                        )
                    }
                }
                is PairingReceiverStatus.Failed -> {
                    PanelTitle(
                        stringResource(if (signIn) R.string.tv_pairing_failed_title_signin else R.string.tv_pairing_failed_title),
                    )
                    Text(
                        text = stringResource(status.code.messageRes(), status.serverName),
                        color = MarqueeColors.Error,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 13.dp),
                    )
                    PanelActions {
                        TvMarqueeButton(
                            text = stringResource(R.string.tv_pairing_try_again),
                            onClick = onCancel,
                            focusRequester = primaryFocus,
                        )
                    }
                }
                else -> Unit
            }
        },
        card = {
            TvMarqueeCard {
                when (status) {
                    is PairingReceiverStatus.AwaitingApproval -> {
                        if (status.userCode.isNotBlank()) {
                            Text(
                                stringResource(R.string.tv_signin_code_label),
                                color = MarqueeColors.InkTertiary,
                                fontSize = 14.sp,
                            )
                            TvMarqueeCodeTiles(DeviceCodeFormat.display(status.userCode), Modifier.padding(top = 11.dp))
                        }
                        olderPhonesMatchWords(status)?.let { words ->
                            Text(
                                text = stringResource(R.string.tv_pairing_confirm_older_phones, words),
                                color = MarqueeColors.InkTertiary,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 11.dp),
                            )
                        }
                        ServerNameLabel(status.serverName, signIn)
                        WaitingDots(Modifier.padding(top = 15.dp))
                    }
                    is PairingReceiverStatus.Pairing -> {
                        ServerNameLabel(status.serverName, signIn)
                        WaitingDots(Modifier.padding(top = 15.dp))
                    }
                    is PairingReceiverStatus.SignedIn, is PairingReceiverStatus.Completed ->
                        TvMarqueeCardSymbol(Icons.Filled.Check, tint = MarqueeColors.Live, size = 70.dp)
                    is PairingReceiverStatus.Failed, is PairingReceiverStatus.Unreachable ->
                        TvMarqueeCardSymbol(Icons.Outlined.ErrorOutline, tint = MarqueeColors.Warning, size = 70.dp)
                    else -> {
                        TvMarqueeCardSymbol(Icons.Outlined.PhoneAndroid, size = 70.dp)
                        WaitingDots(Modifier.padding(top = 17.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun PanelActions(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        content = content,
    )
}

@Composable
private fun CancelButton(onCancel: () -> Unit, focusRequester: FocusRequester? = null) {
    TvMarqueeButton(
        text = stringResource(R.string.tv_pairing_cancel),
        onClick = onCancel,
        kind = TvMarqueeButtonKind.Glass,
        focusRequester = focusRequester,
    )
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

@Composable
private fun PanelTitle(text: String) {
    TvMarqueeHeadline(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun PanelDetail(text: String) {
    TvMarqueeBody(text, size = 16.sp, modifier = Modifier.padding(top = 13.dp))
}

@Composable
private fun ServerNameLabel(serverName: String, signIn: Boolean) {
    Text(
        text = stringResource(if (signIn) R.string.tv_pairing_server_label_signin else R.string.tv_pairing_server_label),
        color = MarqueeColors.InkTertiary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 17.dp),
    )
    Text(
        text = serverName,
        color = MarqueeColors.Ink,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Pulsing dots while something happens on the phone. */
@Composable
private fun WaitingDots(modifier: Modifier = Modifier, count: Int = 5) {
    val reduceMotion = rememberReduceMotion()
    var phase by remember { mutableStateOf(if (reduceMotion) count / 2 else 0) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(280)
                phase = (phase + 1) % (count + 1)
            }
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(6.dp)
                    .background(MarqueeColors.Ink.copy(alpha = if (index <= phase) 0.9f else 0.22f), RoundedCornerShape(999.dp)),
            )
        }
    }
}
