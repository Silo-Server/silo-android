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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SignOutConfirmDialog
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SettingsTextStyles
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.theme.SiloMutedText
import org.siloserver.silo.android.ui.theme.Spacing
import org.siloserver.silo.model.auth.User

/**
 * Settings section showing user account info, device pairing, and sign out.
 *
 * Session management and the admin surface are deliberately absent: both were
 * removed from the Android clients outright — phone, TV, and the shared code
 * that served them — not merely hidden behind a gate.
 */
@Composable
fun AccountSection(
    user: User?,
    isLoadingUser: Boolean,
    onPairDevice: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    // iOS parity: the account header is a button that opens profile
    // selection ("Tap to switch profile") — the chevron was previously dead.
    onSwitchProfile: () -> Unit = {},
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    SettingsSectionCard(modifier = modifier) {
        if (isLoadingUser) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.xxl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Spacing.xxl),
                    strokeWidth = 2.dp,
                )
            }
        } else if (user != null) {
            // Claims the card's first row slot, so the row below it still
            // draws its hairline.
            val headerDivider = settingsRowDividerVisible()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsDimens.rowMinHeight)
                    .settingsRowDivider(headerDivider)
                    .clickable(onClick = onSwitchProfile)
                    .padding(
                        horizontal = SettingsDimens.rowHorizontalPadding,
                        vertical = SettingsDimens.rowVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar — iOS ProfileAvatarView size 56.
                Box(
                    modifier = Modifier
                        .size(SettingsDimens.avatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(SettingsDimens.avatarIconSize),
                    )
                }

                Spacer(modifier = Modifier.width(SettingsDimens.avatarGap))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SettingsDimens.rowLabelGap),
                ) {
                    Text(
                        text = user.username,
                        style = SettingsTextStyles.accountName,
                        color = SiloForeground,
                        maxLines = 1,
                    )
                    Text(
                        text = user.email,
                        style = SettingsTextStyles.rowDescription,
                        color = SiloMutedText,
                        maxLines = 1,
                    )
                }

                Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))

                SettingsRowChevron()
            }

            SettingsNavigationRow(
                label = "Pair device",
                description = "Link a TV or another device to this account.",
                onClick = onPairDevice,
            )

            SettingsDestructiveRow(
                label = "Sign out",
                description = "Sign this device out of ${user.username}'s account.",
                onClick = { confirmSignOut = true },
            )

            // Same dialog the profile menu raises, so the answer to "are you
            // sure?" does not depend on which of the two routes was taken.
            SignOutConfirmDialog(
                visible = confirmSignOut,
                accountName = user.username,
                onConfirm = {
                    confirmSignOut = false
                    onSignOut()
                },
                onDismiss = { confirmSignOut = false },
            )
        }
    }
}
