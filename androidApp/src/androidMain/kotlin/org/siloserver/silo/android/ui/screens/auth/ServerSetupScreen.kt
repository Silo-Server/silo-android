package org.siloserver.silo.android.ui.screens.auth

import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.marquee.MarqueeButton
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorHaptic
import org.siloserver.silo.android.ui.components.marquee.MarqueeErrorText
import org.siloserver.silo.android.ui.components.marquee.MarqueeHeadline
import org.siloserver.silo.android.ui.components.marquee.MarqueeMetrics
import org.siloserver.silo.android.ui.components.marquee.MarqueeSeparator
import org.siloserver.silo.android.ui.components.marquee.MarqueeStage
import org.siloserver.silo.android.ui.components.marquee.MarqueeTextField
import org.siloserver.silo.android.ui.components.marquee.MarqueeWordmark
import org.siloserver.silo.android.ui.components.marquee.marqueePressHaptic
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeScene
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.common.ui.marquee.MarqueeServerMark
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.common.ui.marquee.ServerBrandingCache
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry

/**
 * First screen when no server is configured: the address field over the
 * brand light, recent servers below it, and protocol and port under
 * "Advanced options" (silo-apple `ServerSetupView`).
 *
 * After validating the server:
 * - If the server needs setup, navigates to [onNavigateToSetup].
 * - A saved server this device is still signed in to goes to [onNavigateToProfiles].
 * - Otherwise navigates to [onNavigateToLogin], indicating whether signup is enabled.
 */
@Composable
fun ServerSetupScreen(
    onNavigateToSetup: () -> Unit,
    onNavigateToLogin: (signupEnabled: Boolean) -> Unit,
    onNavigateToProfiles: () -> Unit,
    /** An address to start from, such as the server an app link named. */
    prefillUrl: String? = null,
    viewModel: ServerSetupViewModel = koinViewModel(),
    serverRegistry: ServerRegistry = koinInject(),
    brandingCache: ServerBrandingCache = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val entries by serverRegistry.entries.collectAsState()
    val recents = remember(entries) { entries.sortedByDescending { it.lastUsedAtEpochMs }.take(3) }
    var fieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { MarqueeScene.showGeneric() }
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

            ServerSetupDestination.Profiles -> {
                viewModel.onNavigationConsumed()
                onNavigateToProfiles()
            }

            null -> Unit
        }
    }
    MarqueeErrorHaptic(state.error)

    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    state.pendingCleartextUrl?.let { origin ->
        SiloConfirmDialog(
            title = "Connect without encryption?",
            body = "Your password and what you watch will be sent unencrypted to " +
                "${ServerBranding.hostLabel(origin)}. Only do this on a network you trust.",
            confirmLabel = "Connect",
            destructive = false,
            confirmEnabled = !state.isLoading,
            onConfirm = viewModel::confirmCleartextConnection,
            onDismiss = viewModel::cancelCleartextConnection,
        )
    }

    MarqueeStage(
        scrim = MarqueeScrimStyle.Bottom,
        frostStart = 0.30f,
        topBar = { MarqueeWordmark() },
    ) {
        MarqueeHeadline(title = "Connect to\nyour server", lead = "Type the address you use for Silo.")

        MarqueeTextField(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChanged,
            icon = Icons.Outlined.Language,
            placeholder = "media.example.com",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            onImeAction = viewModel::onConnectClick,
            showsClearButton = true,
            isError = state.error != null,
            modifier = Modifier
                .padding(top = 20.dp)
                .onFocusChanged { fieldFocused = it.hasFocus },
        )

        state.error?.let { error ->
            MarqueeErrorText(error, Modifier.padding(top = 10.dp, start = 4.dp))
        }
        if (state.usesCleartext && state.error == null) {
            Text(
                "This address uses unencrypted HTTP. Fine on a trusted home network; avoid it on public Wi-Fi.",
                color = MarqueeColors.Warning,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
        }

        AdvancedOptions(
            expanded = showAdvanced,
            onToggle = { showAdvanced = !showAdvanced },
            enabled = !state.isLoading,
            scheme = state.selectedScheme,
            onSchemeSelected = viewModel::onSchemeSelected,
            port = state.port,
            onPortChanged = viewModel::onPortChanged,
            modifier = Modifier.padding(top = 12.dp),
        )

        // Recents are for picking before typing; while the address field has
        // the keyboard, the field and Continue need the room.
        if (recents.isNotEmpty() && !fieldFocused) {
            Text(
                "Recent",
                color = MarqueeColors.InkSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 22.dp, start = 2.dp, bottom = 10.dp),
            )
            RecentServers(
                servers = recents,
                brandingCache = brandingCache,
                enabled = !state.isLoading,
                onPick = { server ->
                    viewModel.useRecent(server.url)
                    viewModel.onConnectClick()
                },
            )
        }

        MarqueeButton(
            text = if (state.isLoading) "Connecting…" else "Continue",
            onClick = viewModel::onConnectClick,
            isLoading = state.isLoading,
            enabled = !state.isLoading,
            modifier = Modifier.padding(top = 22.dp),
        )
    }
}

@Composable
private fun AdvancedOptions(
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    scheme: ServerSetupScheme,
    onSchemeSelected: (ServerSetupScheme) -> Unit,
    port: String,
    onPortChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().animateContentSize()) {
        val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "advancedChevron")
        val interaction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onToggle)
                .marqueePressHaptic(interaction)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(start = 4.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null, tint = MarqueeColors.InkSecondary, modifier = Modifier.size(16.dp))
            Text("Advanced options", color = MarqueeColors.InkSecondary, fontSize = 14.sp)
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MarqueeColors.InkSecondary,
                modifier = Modifier.size(16.dp).rotate(chevron),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shape = RoundedCornerShape(MarqueeMetrics.FieldCorner)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(MarqueeMetrics.FieldHeight).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Protocol", color = MarqueeColors.Ink, fontSize = MarqueeMetrics.FieldFont, modifier = Modifier.weight(1f))
                        SegmentedPicker(
                            options = ServerSetupScheme.entries,
                            selected = scheme,
                            label = { it.label },
                            onSelect = onSchemeSelected,
                        )
                    }
                    MarqueeSeparator()
                    Row(
                        Modifier.fillMaxWidth().height(MarqueeMetrics.FieldHeight).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Port", color = MarqueeColors.Ink, fontSize = MarqueeMetrics.FieldFont, modifier = Modifier.weight(1f))
                        Box(Modifier.width(120.dp), contentAlignment = Alignment.CenterEnd) {
                            if (port.isEmpty()) {
                                Text("Auto", color = MarqueeColors.InkTertiary, fontSize = MarqueeMetrics.FieldFont)
                            }
                            BasicTextField(
                                value = port,
                                onValueChange = onPortChanged,
                                singleLine = true,
                                textStyle = TextStyle(color = MarqueeColors.Ink, fontSize = MarqueeMetrics.FieldFont, textAlign = TextAlign.End),
                                cursorBrush = SolidColor(Color(0xFF0A84FF)),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Port" },
                            )
                        }
                    }
                }
                Text(
                    "Auto tries HTTPS first, then HTTP, then Silo's port 8090.",
                    color = MarqueeColors.InkTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** A small iOS-style segmented control. */
@Composable
private fun <T> SegmentedPicker(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .width(190.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.28f) else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = MarqueeColors.Ink,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RecentServers(
    servers: List<ServerEntry>,
    brandingCache: ServerBrandingCache,
    enabled: Boolean,
    onPick: (ServerEntry) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
    ) {
        servers.forEachIndexed { index, server ->
            if (index > 0) MarqueeSeparator()
            val host = ServerBranding.hostLabel(server.url)
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null) { onPick(server) }
                    .marqueePressHaptic(interaction)
                    .semantics { contentDescription = "${server.displayName}, $host" }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarqueeServerMark(
                    name = server.fetchedName,
                    imageUrl = brandingCache.branding(server.url)?.markUrl,
                    size = 40.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        server.displayName,
                        color = MarqueeColors.Ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(host, color = MarqueeColors.InkTertiary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MarqueeColors.InkTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
