package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.siloserver.silo.android.BuildConfig
import org.siloserver.silo.common.network.normalizedClientBuildNumber

/**
 * Connection section. Mirrors the iOS phone Settings `Server` row: a
 * single teal-badged `server.rack` row whose trailing value is the
 * active server label, with a disclosure chevron that opens the server
 * list.
 */
@Composable
fun ServerInfoSection(
    serverUrl: String,
    onManageServersClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SettingsSectionCard(modifier = modifier) {
        SettingsRowLabel(
            title = "Server",
            icon = Icons.Default.Dns,
            badgeColor = SettingsBadgeTeal,
            value = serverUrl.ifBlank { "Not connected" },
            onClick = onManageServersClick,
            showChevron = true,
        )
        SettingsRowLabel(
            title = "Version",
            icon = Icons.Default.Info,
            badgeColor = SettingsBadgeGray,
            // Includes the build number so a support report and the server's
            // admin Activity page name the exact same build. Unstamped local
            // builds show the bare version rather than a meaningless "(0)",
            // matching what those builds report to the server.
            value = normalizedClientBuildNumber(BuildConfig.BUILD_NUMBER)
                ?.let { build -> "${BuildConfig.VERSION_NAME} ($build)" }
                ?: BuildConfig.VERSION_NAME,
        )
    }
}
