package org.siloserver.silo.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.screens.profiles.ProfileAvatar
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ServerRegistry

/**
 * The profile-avatar dropdown, in one place.
 *
 * Home, Libraries and the shared top bar each paint their own avatar button —
 * a chip on the floating bar, a bare 40dp target on the two screens that own
 * their chrome — but the menu behind all three was the same six items copied
 * three times, which is how "Switch Profile" survived the move to sentence
 * case in three files at once. The anchors stay where they are; the menu is
 * this.
 *
 * Item order and gating are unchanged. A null [onRequestsClick] is a server
 * with `requests_enabled` off, and a null [onWatchPartyClick] is the
 * Settings → Experimental → Watch Party gate; neither is ever shown unconditionally, and
 * nothing new was added. Reading/ebooks are phone-only and reached from
 * Libraries, and Requests keeps its two entry points (this menu and search).
 *
 * Sign out is gated by [SignOutConfirmDialog] — the same dialog the settings
 * Account card raises, so the confirmation does not depend on the route taken.
 */
@Composable
fun ProfileMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onRequestsClick: (() -> Unit)? = null,
    onWatchPartyClick: (() -> Unit)? = null,
    // Who is signed in, for the header. Null hides the header.
    activeProfile: Profile? = null,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val serverRegistry: ServerRegistry = koinInject()
    val activeServer by serverRegistry.activeEntry.collectAsState()

    // Whether anything sits above the account actions.
    //
    // The menu carries exactly one hairline, and this is what decides whether
    // it is drawn at all. A settings card rules every row but its first and
    // separates *groups* by being a different card; a single popup cannot be
    // two cards, so ruling every row here would spend the same line on both
    // jobs and the feature/account split would stop reading as a split. The
    // old menu drew its divider unconditionally, so a server with requests
    // disabled opened onto a stray rule above its first item.
    val hasFeatureGroup = onRequestsClick != null || onWatchPartyClick != null

    SiloDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        if (activeProfile != null) {
            SiloMenuHeader(
                title = activeProfile.name,
                detail = activeServer?.let { server ->
                    listOf(server.displayName, ServerBranding.hostLabel(server.url)).distinct().joinToString(" · ")
                },
                leading = {
                    ProfileAvatar(avatar = activeProfile.avatarRef(), name = activeProfile.name, size = 40.dp)
                },
            )
            SiloMenuDivider()
        }
        if (onRequestsClick != null) {
            SiloMenuItem(
                label = "Requests",
                icon = Icons.Rounded.Inbox,
                onClick = {
                    onDismissRequest()
                    onRequestsClick()
                },
            )
        }
        if (onWatchPartyClick != null) {
            SiloMenuItem(
                label = "Watch Party",
                icon = Icons.Rounded.Groups,
                onClick = {
                    onDismissRequest()
                    onWatchPartyClick()
                },
            )
        }
        SiloMenuItem(
            label = "Settings",
            icon = Icons.Rounded.Settings,
            showDivider = hasFeatureGroup,
            onClick = {
                onDismissRequest()
                onSettingsClick()
            },
        )
        SiloMenuItem(
            label = "Switch profile",
            icon = Icons.Rounded.SwitchAccount,
            onClick = {
                onDismissRequest()
                onSwitchProfileClick()
            },
        )
        SiloMenuItem(
            label = "Switch server",
            icon = Icons.Rounded.Dns,
            onClick = {
                onDismissRequest()
                onSwitchServerClick()
            },
        )
        SiloMenuItem(
            label = "Sign out",
            icon = Icons.AutoMirrored.Rounded.Logout,
            destructive = true,
            showDivider = true,
            onClick = {
                onDismissRequest()
                confirmSignOut = true
            },
        )
    }

    SignOutConfirmDialog(
        visible = confirmSignOut,
        onConfirm = {
            confirmSignOut = false
            onSignOutClick()
        },
        onDismiss = { confirmSignOut = false },
    )
}
