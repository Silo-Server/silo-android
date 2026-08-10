package org.siloserver.silo.tv.ui.screens.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.Spacing

/**
 * TV admin hub — the landing surface for admin management. Lists the admin
 * sub-sections and routes into each. Mirrors the phone's
 * `AdminHubScreen` (Dashboard / Users / Sessions / Logs / Scans) but adapts the
 * UI to 10-foot/D-pad TV with focusable [Card] rows.
 *
 * Entry to admin is already gated by the Settings surface
 * ([TvSettingsViewModel.UiState.adminVisible] = acting-admin + client policy),
 * so this hub does not re-run the gate; it is only reachable when admin is
 * visible. All sub-sections (Dashboard / Users / Sessions / Scans / Logs) route
 * to their TV screens.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAdminHubScreen(
    onOpenDashboard: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenScans: () -> Unit,
    onOpenLogs: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = true) { onBack() }

    val firstRowFocus = remember { FocusRequester() }
    var hubHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = firstRowFocus::requestFocus,
            isFocused = { hubHasFocus },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { hubHasFocus = it.hasFocus }
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvAdminScreenHeader(eyebrow = "ADMIN", title = "Admin")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Top 8dp is the gap under the header; the bottom edge is real
            // screen overscan and takes the safe-area token.
            contentPadding = PaddingValues(
                start = Spacing.safeArea,
                end = Spacing.safeArea,
                top = 8.dp,
                bottom = Spacing.safeAreaVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HubRow(
                    icon = Icons.Filled.Dashboard,
                    title = "Dashboard",
                    subtitle = "Server stats & activity",
                    onClick = onOpenDashboard,
                    focusRequester = firstRowFocus,
                )
            }
            item {
                HubRow(
                    icon = Icons.Filled.People,
                    title = "Users",
                    subtitle = "Manage accounts & access",
                    onClick = onOpenUsers,
                )
            }
            item {
                HubRow(
                    icon = Icons.Filled.PlayCircle,
                    title = "Sessions",
                    subtitle = "Now playing & controls",
                    onClick = onOpenSessions,
                )
            }
            item {
                HubRow(
                    icon = Icons.Filled.Refresh,
                    title = "Scans",
                    subtitle = "Rescan libraries for new media",
                    onClick = onOpenScans,
                )
            }
            item {
                HubRow(
                    icon = Icons.AutoMirrored.Filled.Article,
                    title = "Logs",
                    subtitle = "App & audit logs",
                    onClick = onOpenLogs,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = RoundedCornerShape(16.dp)),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .widthIn(max = 960.dp)
            .heightIn(min = 44.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
