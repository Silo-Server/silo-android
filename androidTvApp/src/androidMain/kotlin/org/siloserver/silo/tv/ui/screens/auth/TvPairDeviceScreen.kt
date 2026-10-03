package org.siloserver.silo.tv.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.tv.R
import org.siloserver.silo.viewmodel.DevicePairingError
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.tv.ui.focus.tvControlSemantics
import org.siloserver.silo.tv.ui.focus.tvPairDeviceFocusTarget
import org.siloserver.silo.tv.ui.focus.TvPairDeviceAction
import org.siloserver.silo.tv.ui.components.TvTextInputDialog
import org.siloserver.silo.viewmodel.DevicePairingViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * TV Device Pairing — a signed-in TV user approves or denies another device's
 * login. Reached from a `silo://device?token=…` deep link (the TV browser/QR
 * flow on the other device) or from the Settings "Pair a device" row, where the
 * user types the code shown on the other screen.
 *
 * Mirrors the phone `DevicePairingScreen` over the same shared
 * [DevicePairingViewModel]; D-pad-adapted with focusable TV [Button]s and a
 * [TvTextInputDialog] for manual code entry (no inline text fields on TV).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPairDeviceScreen(
    token: String?,
    code: String?,
    onDone: () -> Unit,
    onSignIn: () -> Unit,
    viewModel: DevicePairingViewModel = koinViewModel(
        parameters = { parametersOf(token to code) },
    ),
) {
    val state by viewModel.uiState.collectAsState()
    var showCodeEntry by remember { mutableStateOf(false) }
    val enterCodeFocus = remember { FocusRequester() }
    val checkFocus = remember { FocusRequester() }
    val approveFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }

    BackHandler(enabled = true) { onDone() }

    // Manual code entry is only meaningful before a token-driven lookup and
    // before a decision lands; matches the phone's editable-field gating.
    val canEnterCode = state.token.isNullOrBlank() && state.completedStatus == null

    // Approve and Deny exist to act on a specific request, and the request only
    // exists once the lookup resolves — a deep link arrives with its token
    // already set, so "there is an identifier" was never the right gate.
    //
    // The two halves are separated because they are different kinds of
    // disabled. With no lookup the decision cannot apply at all, so the buttons
    // leave the focus graph rather than sitting there as dead stops the D-pad
    // walks onto (TV Material keeps disabled buttons focusable, so `enabled`
    // alone would not do that — see tvControlSemantics). A decision already in
    // flight is momentary, so those stay focusable and merely refuse to act,
    // which is what keeps focus from being dropped mid-submit.
    val decisionState = TvControlState(
        focusable = state.lookup != null,
        actionable = state.canDecide,
    )
    // Check is gated transiently: it is the only control on a token route
    // before the lookup resolves, and it is what the focus target falls back
    // to, so it has to stay in the focus graph while its own lookup runs.
    val checkState = TvControlState.transient(!state.isLoading && !state.isSubmitting)

    val focusTarget = tvPairDeviceFocusTarget(
        hasCompleted = state.completedStatus != null,
        hasResolvedLookup = state.lookup != null,
        canEnterCode = canEnterCode,
    )
    val actionFocus = rememberTvContentInitialFocus(
        target = when (focusTarget) {
            TvPairDeviceAction.EnterCode -> enterCodeFocus
            TvPairDeviceAction.Check -> checkFocus
            TvPairDeviceAction.Approve -> approveFocus
            TvPairDeviceAction.Done -> doneFocus
        },
        contentKey = focusTarget,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(actionFocus)
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // 480dp content width plus the 48dp horizontal padding on
                // each side. On narrower displays fillMaxWidth keeps the
                // panel responsive instead of clipping long match phrases.
                .widthIn(max = 576.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Pair Device",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.lookup?.deviceName?.let { "Approve sign-in for $it" }
                    ?: "Approve a sign-in request from another device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            val shownCode = state.lookup?.userCode ?: state.code
            if (shownCode.isNotBlank()) {
                Text(
                    text = shownCode,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                )
            }

            state.lookup?.let { lookup -> PairingDetails(lookup) }

            state.error?.let { error ->
                Spacer(Modifier.height(18.dp))
                Text(
                    text = error.message(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                )
                if (error == DevicePairingError.SignInFirst) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onSignIn) { Text("Sign In") }
                }
            }

            state.completedStatus?.let { status ->
                Spacer(Modifier.height(18.dp))
                Text(
                    text = if (status == "approved") "Device approved." else "Device denied.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(28.dp))

            if (state.completedStatus == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (canEnterCode) {
                        Button(
                            onClick = { showCodeEntry = true },
                            modifier = Modifier.focusRequester(enterCodeFocus),
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Enter code")
                        }
                    }
                    Button(
                        onClick = { checkState.perform { viewModel.lookup() } },
                        enabled = checkState.focusable,
                        modifier = Modifier
                            .focusRequester(checkFocus)
                            .tvControlSemantics(checkState),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isLoading) "Checking…" else "Check")
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { decisionState.perform(viewModel::deny) },
                        enabled = decisionState.focusable,
                        modifier = Modifier.tvControlSemantics(decisionState),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Deny")
                    }
                    Button(
                        onClick = { decisionState.perform(viewModel::approve) },
                        enabled = decisionState.focusable,
                        modifier = Modifier
                            .focusRequester(approveFocus)
                            .tvControlSemantics(decisionState),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isSubmitting) "Approving…" else "Approve")
                    }
                }
            } else {
                Button(
                    onClick = onDone,
                    modifier = Modifier.focusRequester(doneFocus),
                ) {
                    Text("Done")
                }
            }
        }
    }

    if (showCodeEntry) {
        TvTextInputDialog(
            title = "Enter code",
            label = "Code shown on the other device",
            confirmLabel = "Look up",
            initialValue = state.code,
            onConfirm = { entered ->
                viewModel.onCodeChanged(entered)
                showCodeEntry = false
                viewModel.lookup()
            },
            onDismiss = { showCodeEntry = false },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PairingDetails(lookup: DeviceLoginLookupResponse) {
    Spacer(Modifier.height(18.dp))
    // The code the other device shows, grouped 4+4; the match words are no
    // longer shown anywhere.
    DetailRow(
        label = "Code",
        value = lookup.userCode?.let(org.siloserver.silo.model.auth.DeviceCodeFormat::display).orEmpty(),
    )
    DetailRow(label = "Device", value = lookup.deviceName.orEmpty())
    DetailRow(label = "Platform", value = lookup.devicePlatform.orEmpty())
    lookup.ipAddressHint?.takeIf { it.isNotBlank() }?.let { DetailRow(label = "IP", value = it) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DevicePairingError.message(): String = when (this) {
    DevicePairingError.EnterCode -> stringResource(R.string.tv_pair_error_enter_code)
    DevicePairingError.SignInFirst -> stringResource(R.string.tv_pair_error_sign_in_first)
    DevicePairingError.NotFound -> stringResource(R.string.tv_pair_error_not_found)
    is DevicePairingError.NotFoundOn -> stringResource(R.string.tv_pair_error_not_found_on, serverName)
    DevicePairingError.Expired -> stringResource(R.string.tv_pair_error_expired)
    DevicePairingError.AlreadySignedIn -> stringResource(R.string.tv_pair_error_already_signed_in)
    DevicePairingError.Declined -> stringResource(R.string.tv_pair_error_declined)
    DevicePairingError.Canceled -> stringResource(R.string.tv_pair_error_canceled)
    DevicePairingError.Network -> stringResource(R.string.tv_pair_error_network)
    DevicePairingError.ProviderUnavailable -> stringResource(R.string.tv_pair_error_provider_unavailable)
    is DevicePairingError.Server -> message ?: stringResource(R.string.tv_pair_error_generic)
}
