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
import androidx.compose.ui.graphics.vector.ImageVector
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
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.pairing.PairingReceiverStatus
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.pairing.PairingFailureCode
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
    TvMarqueeScreen(
        modifier = modifier.onFocusChanged { panelHasFocus = it.hasFocus },
        copy = {
            when (status) {
                PairingReceiverStatus.Connected -> {
                    PanelChip(stringResource(CommonR.string.tvPairing_chip_connected), spinner = true)
                    PanelTitle(
                        stringResource(if (signIn) CommonR.string.tvPairing_connectedTitleSignin else CommonR.string.tvPairing_connectedTitle),
                    )
                    PanelDetail(
                        stringResource(
                            if (signIn) CommonR.string.tvPairing_connectedDetailSignin else CommonR.string.tvPairing_connectedDetail,
                        ),
                    )
                    PanelActions { CancelButton(onCancel, primaryFocus) }
                }
                is PairingReceiverStatus.ConsentRequested -> {
                    PanelChip(
                        stringResource(if (signIn) CommonR.string.tvPairing_chip_consentSignin else CommonR.string.tvPairing_chip_consent),
                        icon = Icons.Outlined.PhoneAndroid,
                    )
                    PanelTitle(
                        stringResource(if (signIn) CommonR.string.tvPairing_consentTitleSignin else CommonR.string.tvPairing_consentTitle),
                    )
                    PanelDetail(stringResource(CommonR.string.tvPairing_consentDetail, status.serverName))
                    if (status.unencrypted) {
                        PanelDetail(stringResource(CommonR.string.tvPairing_consentUnencrypted, DeviceCodeFormat.host(status.serverURL)))
                    }
                    PanelActions {
                        TvMarqueeButton(text = stringResource(CommonR.string.tvPairing_allow), onClick = onAllow, focusRequester = primaryFocus)
                        TvMarqueeButton(text = stringResource(CommonR.string.tvPairing_dontAllow), onClick = onDeny, kind = TvMarqueeButtonKind.Glass)
                    }
                    PanelNote(
                        stringResource(if (signIn) CommonR.string.tvPairing_consentNoteSignin else CommonR.string.tvPairing_consentNote),
                        Modifier.padding(top = 15.dp),
                    )
                }
                is PairingReceiverStatus.Pairing -> {
                    PanelChip(stringResource(CommonR.string.tvPairing_chip_working), spinner = true)
                    PanelTitle(
                        status.accountName?.let { stringResource(CommonR.string.tvPairing_workingTitleAccount, it, status.serverName) }
                            ?: stringResource(CommonR.string.tvPairing_workingTitle, status.serverName),
                    )
                    PanelDetail(stringResource(CommonR.string.tvPairing_workingDetail))
                    PanelActions { CancelButton(onCancel, primaryFocus) }
                }
                is PairingReceiverStatus.Unreachable -> {
                    PanelTitle(stringResource(CommonR.string.tvPairing_unreachableTitle, status.serverName))
                    val providerName = status.providerName
                    PanelDetail(
                        if (providerName != null) {
                            stringResource(CommonR.string.tvPairing_unreachableProvider, status.serverName, providerName)
                        } else {
                            stringResource(
                                CommonR.string.tvPairing_unreachablePublic,
                                status.serverName,
                                DeviceCodeFormat.host(status.serverURL),
                            )
                        },
                    )
                    PanelActions {
                        val alternate = status.alternateUrl
                        if (alternate != null) {
                            TvMarqueeButton(
                                text = stringResource(CommonR.string.tvPairing_useAlternate, DeviceCodeFormat.host(alternate)),
                                onClick = onUseAlternate,
                                focusRequester = primaryFocus,
                            )
                            TvMarqueeButton(
                                text = stringResource(CommonR.string.common_tryAgain),
                                onClick = onRetryAddress,
                                kind = TvMarqueeButtonKind.Glass,
                            )
                        } else {
                            TvMarqueeButton(
                                text = stringResource(CommonR.string.common_tryAgain),
                                onClick = onRetryAddress,
                                focusRequester = primaryFocus,
                            )
                        }
                        TvMarqueeButton(
                            text = stringResource(CommonR.string.common_cancel),
                            onClick = onCancel,
                            kind = TvMarqueeButtonKind.Plain,
                        )
                    }
                }
                is PairingReceiverStatus.AwaitingApproval -> {
                    PanelChip(
                        stringResource(if (status.automatic) CommonR.string.tvPairing_chip_approving else CommonR.string.tvPairing_chip_waiting),
                        spinner = true,
                    )
                    PanelTitle(
                        stringResource(
                            if (status.automatic) CommonR.string.tvPairing_confirmTitleAutomatic else CommonR.string.tvPairing_confirmTitle,
                        ),
                    )
                    PanelDetail(
                        stringResource(
                            if (status.automatic) CommonR.string.tvPairing_confirmDetailAutomatic else CommonR.string.tvPairing_confirmDetail,
                        ),
                    )
                    PanelActions { CancelButton(onCancel, primaryFocus) }
                }
                is PairingReceiverStatus.SignedIn -> {
                    PanelTitle(
                        if (status.serverCount <= 1) {
                            stringResource(CommonR.string.tvPairing_signedIn)
                        } else {
                            stringResource(CommonR.string.tvPairing_signedInCount, status.serverCount)
                        },
                    )
                    // No Cancel: this server's sign-in is already saved.
                    PanelDetail(stringResource(CommonR.string.tvPairing_finishing))
                }
                is PairingReceiverStatus.Completed -> {
                    PanelTitle(stringResource(CommonR.string.tvPairing_completedTitle))
                    val accountCount = status.accounts.distinct().size
                    PanelDetail(
                        if (accountCount > 1) {
                            // Several accounts end on Change Account, not a profile picker.
                            stringResource(CommonR.string.tvPairing_completedChooseAccount, accountCount)
                        } else if (status.serverNames.isEmpty()) {
                            stringResource(CommonR.string.tvPairing_completedNone)
                        } else {
                            stringResource(CommonR.string.tvPairing_completedNamed, status.serverNames.joinToString(", "))
                        },
                    )
                    PanelActions {
                        TvMarqueeButton(
                            text = stringResource(CommonR.string.tvPairing_continue),
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            onClick = onContinue,
                            focusRequester = primaryFocus,
                        )
                    }
                }
                is PairingReceiverStatus.Failed -> {
                    PanelTitle(
                        stringResource(if (signIn) CommonR.string.tvPairing_failedTitleSignin else CommonR.string.tvPairing_failedTitle),
                    )
                    PanelDetail(stringResource(status.code.messageRes(signIn), status.serverName))
                    PanelActions {
                        TvMarqueeButton(
                            text = stringResource(CommonR.string.common_tryAgain),
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
                    PairingReceiverStatus.Connected -> {
                        TvMarqueeCardSymbol(Icons.Outlined.PhoneAndroid, size = 70.dp)
                        CardTitle(stringResource(CommonR.string.tvPairing_card_onPhone))
                        CardNote(
                            stringResource(
                                if (signIn) CommonR.string.tvPairing_card_onPhoneNoteSignin else CommonR.string.tvPairing_card_onPhoneNote,
                            ),
                        )
                    }
                    is PairingReceiverStatus.ConsentRequested -> {
                        TvMarqueeCardSymbol(Icons.Outlined.PhoneAndroid, size = 70.dp)
                        WaitingDots(Modifier.padding(top = 17.dp))
                        CardTitle(status.serverName)
                        CardNote(stringResource(CommonR.string.tvPairing_card_consentNote))
                    }
                    is PairingReceiverStatus.AwaitingApproval -> {
                        if (status.userCode.isNotBlank()) {
                            Text(
                                stringResource(CommonR.string.tvSignIn_codeLabel),
                                color = MarqueeColors.InkTertiary,
                                fontSize = 14.sp,
                            )
                            TvMarqueeCodeTiles(DeviceCodeFormat.display(status.userCode), Modifier.padding(top = 11.dp))
                        }
                        olderPhonesMatchWords(status)?.let { words -> CardNote(stringResource(CommonR.string.tvPairing_confirmOlderPhones, words)) }
                        // Every push shows its own code, naming the account
                        // the phone said it signs in ("sam on Home").
                        CardTitle(
                            status.accountName?.let { stringResourceWithNames(CommonR.string.tvPairing_accountOnServer, it, status.serverName) }
                                ?: status.serverName,
                        )
                    }
                    is PairingReceiverStatus.Pairing -> {
                        WaitingDots()
                        CardTitle(
                            status.accountName?.let { stringResourceWithNames(CommonR.string.tvPairing_accountOnServer, it, status.serverName) }
                                ?: status.serverName,
                        )
                    }
                    is PairingReceiverStatus.SignedIn, is PairingReceiverStatus.Completed -> {
                        TvMarqueeCardSymbol(Icons.Filled.Check, tint = MarqueeColors.Live, size = 70.dp)
                        CardTitle(stringResource(CommonR.string.tvPairing_signedIn))
                    }
                    is PairingReceiverStatus.Unreachable -> {
                        TvMarqueeCardSymbol(Icons.Outlined.ErrorOutline, tint = MarqueeColors.Warning, size = 70.dp)
                        CardTitle(stringResource(CommonR.string.tvPairing_card_noAnswer))
                        CardNote(stringResource(CommonR.string.tvPairing_card_noAnswerNote))
                    }
                    is PairingReceiverStatus.Failed -> {
                        TvMarqueeCardSymbol(Icons.Outlined.ErrorOutline, tint = MarqueeColors.Warning, size = 70.dp)
                        CardTitle(stringResource(CommonR.string.tvPairing_card_notFinished))
                        // Earlier servers in the same session may have signed in.
                        CardNote(stringResource(CommonR.string.tvPairing_card_notFinishedNote, status.serverName))
                    }
                    else -> Unit
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
        text = stringResource(CommonR.string.common_cancel),
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

private fun PairingFailureCode.messageRes(signIn: Boolean): Int = when (this) {
    PairingFailureCode.Denied -> CommonR.string.tvPairing_failedDenied
    PairingFailureCode.Expired -> CommonR.string.tvPairing_failedExpired
    PairingFailureCode.Unreachable -> CommonR.string.tvPairing_failedUnreachable
    PairingFailureCode.IdentityMismatch ->
        if (signIn) CommonR.string.tvPairing_failedIdentitySignin else CommonR.string.tvPairing_failedIdentity
    PairingFailureCode.UpdateRequired -> CommonR.string.tvPairing_failedUpdate
    // This TV keeps every sign-in; only a TV with a storage limit sends it.
    PairingFailureCode.AuthFailed, PairingFailureCode.LimitReached -> CommonR.string.tvPairing_failedGeneric
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
private fun PanelChip(text: String, spinner: Boolean = false, icon: ImageVector? = null) {
    TvMarqueeStatusChip(text, showsSpinner = spinner, icon = icon, modifier = Modifier.padding(bottom = 18.dp))
}

@Composable
private fun PanelNote(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MarqueeColors.InkTertiary, fontSize = 14.sp, modifier = modifier)
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        color = MarqueeColors.Ink,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.padding(top = 17.dp),
    )
}

@Composable
private fun CardNote(text: String) {
    Text(
        text = text,
        color = MarqueeColors.InkTertiary,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 7.dp),
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
