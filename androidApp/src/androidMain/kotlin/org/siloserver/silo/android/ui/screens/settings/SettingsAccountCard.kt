package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.screens.profiles.ProfileAvatar
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SettingsTextStyles
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceVariant
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.model.profile.Profile

/**
 * The account card at the top of Settings (Apple `SettingsAccountCard`): the
 * active profile's avatar and name, the account and server underneath, an
 * "Admin" capsule for administrators, and a chevron. Tapping it switches
 * profile.
 *
 * Sign Out sits at the foot of the overview and Pair Device under Connection,
 * as on the Apple apps. Session management and the admin surface stay absent:
 * both were removed from the Android clients outright.
 */
@Composable
fun SettingsAccountCard(
    profile: Profile?,
    user: User?,
    serverHost: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = profile?.name?.takeIf { it.isNotBlank() }
        ?: user?.username?.takeIf { it.isNotBlank() }
        ?: "Switch Profile"
    val username = user?.username?.takeIf { it.isNotBlank() }
    val subtitle = when {
        username != null && username != name && serverHost != null -> "$username · $serverHost"
        serverHost != null -> serverHost
        username != null && username != name -> username
        else -> "Tap to switch profile"
    }
    val isAdministrator = user?.role.equals("admin", ignoreCase = true)

    SettingsSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsDimens.overviewRowMinHeight)
                .clickable(role = Role.Button, onClickLabel = "Switch profile", onClick = onClick)
                .padding(horizontal = SettingsDimens.rowHorizontalPadding, vertical = SettingsDimens.rowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (profile != null) {
                ProfileAvatar(
                    avatar = profile.avatarRef(),
                    name = profile.name,
                    size = SettingsDimens.avatarSize,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(SettingsDimens.avatarSize)
                        .clip(CircleShape)
                        .background(SiloSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = SiloSecondaryText,
                        modifier = Modifier.size(SettingsDimens.avatarSize / 2),
                    )
                }
            }

            Spacer(modifier = Modifier.width(SettingsDimens.avatarGap))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SettingsDimens.rowLabelGap),
            ) {
                Text(
                    text = name,
                    style = SettingsTextStyles.accountName,
                    color = SiloOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = SettingsTextStyles.rowDescription,
                    color = SiloSecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isAdministrator) {
                Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
                Text(
                    text = "Admin",
                    style = SettingsTextStyles.badge,
                    color = SiloSecondaryText,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
            SettingsRowChevron()
        }
    }
}
