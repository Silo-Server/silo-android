package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import org.siloserver.silo.android.R
import org.siloserver.silo.android.ui.components.aurora.AuroraErrorLabel
import org.siloserver.silo.android.ui.components.aurora.AuroraEyebrow
import org.siloserver.silo.android.ui.components.aurora.AuroraFieldLabel
import org.siloserver.silo.android.ui.components.aurora.AuroraGhostButton
import org.siloserver.silo.android.ui.components.aurora.AuroraInk
import org.siloserver.silo.android.ui.components.aurora.AuroraPrimaryButton
import org.siloserver.silo.android.ui.components.aurora.AuroraScreen
import org.siloserver.silo.android.ui.components.aurora.AuroraSegment
import org.siloserver.silo.android.ui.components.aurora.AuroraTextField
import org.siloserver.silo.android.ui.components.aurora.AuroraVariant
import org.siloserver.silo.android.ui.components.aurora.auroraGlass
import org.koin.compose.viewmodel.koinViewModel

/**
 * First screen the user sees. Allows entering the Silo server URL.
 *
 * Rebuilt to match silo-apple's `ServerSetupView` (Aurora): a single centered
 * glass form over the plum backdrop. Protocol + port stay tucked under
 * "Advanced options".
 *
 * After validating the server:
 * - If the server needs setup, navigates to [onNavigateToSetup].
 * - Otherwise navigates to [onNavigateToLogin], indicating whether signup is
 *   enabled.
 */
@Composable
fun ServerSetupScreen(
    onNavigateToSetup: () -> Unit,
    onNavigateToLogin: (signupEnabled: Boolean) -> Unit,
    /** An address to start from, such as the server an app link named. */
    prefillUrl: String? = null,
    viewModel: ServerSetupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(prefillUrl) {
        prefillUrl?.takeIf { it.isNotBlank() }?.let(viewModel::prefill)
    }

    // React to navigation events produced by the ViewModel.
    LaunchedEffect(state.navigateTo) {
        when (val dest = state.navigateTo) {
            is ServerSetupDestination.Setup -> {
                viewModel.onNavigationConsumed()
                onNavigateToSetup()
            }

            is ServerSetupDestination.Login -> {
                viewModel.onNavigationConsumed()
                onNavigateToLogin(dest.signupEnabled)
            }

            null -> Unit
        }
    }

    var showAdvanced by rememberSaveable { mutableStateOf(false) }

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
                    modifier = Modifier.width(140.dp),
                    isLoading = state.isLoading,
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

    AuroraScreen(variant = AuroraVariant.Server) {
        // Silo wordmark (iOS width 132).
        Image(
            painter = painterResource(id = R.drawable.silo_wordmark),
            contentDescription = "Silo",
            modifier = Modifier
                .width(132.dp)
                .height(40.dp),
            contentScale = ContentScale.Fit,
        )

        Spacer(Modifier.height(30.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AuroraEyebrow(text = "Step 01 — Connect", centered = true)
            Text(
                text = "Add your server",
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AuroraInk,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Glass card.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .auroraGlass(cornerRadius = 24.dp, emphasized = true)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AuroraTextField(
                label = "Server address",
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChanged,
                placeholder = "media.example.com",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onImeAction = viewModel::onConnectClick,
            )

            // Advanced options disclosure.
            val chevronRotation by animateFloatAsState(
                targetValue = if (showAdvanced) 180f else 0f,
                label = "advancedChevron",
            )
            AuroraGhostButton(
                label = "Advanced options",
                onClick = { showAdvanced = !showAdvanced },
                fillMaxWidth = true,
                trailing = {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = AuroraInk.copy(alpha = 0.62f),
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(chevronRotation),
                    )
                },
            )

            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuroraFieldLabel("Protocol")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ServerSetupScheme.Auto,
                                ServerSetupScheme.Https,
                                ServerSetupScheme.Http,
                            ).forEach { scheme ->
                                AuroraSegment(
                                    title = scheme.label,
                                    isSelected = state.selectedScheme == scheme,
                                    onClick = { viewModel.onSchemeSelected(scheme) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    AuroraTextField(
                        label = "Port",
                        value = state.port,
                        onValueChange = viewModel::onPortChanged,
                        placeholder = "8090",
                        keyboardType = KeyboardType.Number,
                    )
                }
            }

            state.error?.let { error ->
                AuroraErrorLabel(error)
            }

            if (state.usesCleartext && state.error == null) {
                androidx.compose.material3.Text(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    text = "This server uses unencrypted HTTP. Fine on a trusted home network; " +
                        "avoid it on public Wi-Fi.",
                    fontSize = 12.sp,
                    color = org.siloserver.silo.android.ui.theme.SiloWarning,
                )
            }

            AuroraPrimaryButton(
                label = if (state.isLoading) "Connecting…" else "Connect",
                onClick = viewModel::onConnectClick,
                isLoading = state.isLoading,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
