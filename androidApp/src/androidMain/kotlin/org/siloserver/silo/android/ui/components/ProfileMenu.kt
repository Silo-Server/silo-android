package org.siloserver.silo.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.navigation.LocalAddProfile
import org.siloserver.silo.android.ui.screens.profiles.ProfileAvatar
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.server.ProfileMenuRow
import org.siloserver.silo.model.server.profileMenuRow
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager

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
 * It signs out of the active account only (multi-account spec §4.3). Switch
 * profile opens the open account's picker; Change account (in Switch
 * Server's old place) lists every saved server's accounts.
 *
 * The profile row follows the open account ([profileMenuRow]): Switch profile
 * while it has several profiles; Add Profile while it has one and room for
 * another, since there is nothing to switch to; no row while it has one and
 * its limit is one. Change account is always there.
 */
@Composable
fun ProfileMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onChangeAccountClick: () -> Unit,
    // True for "Sign out & remove server".
    onSignOutClick: (removeServer: Boolean) -> Unit,
    onRequestsClick: (() -> Unit)? = null,
    onWatchPartyClick: (() -> Unit)? = null,
    // Who is signed in, for the header. Null hides the header.
    activeProfile: Profile? = null,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val serverRegistry: ServerRegistry = koinInject()
    val activeServer by serverRegistry.activeEntry.collectAsState()
    val activeAccount by serverRegistry.activeAccount.collectAsState()
    val accounts by serverRegistry.accounts.collectAsState()
    val addProfile = LocalAddProfile.current
    val tokenManager: TokenManager = koinInject()
    // A remote-playback handoff session can neither switch nor add.
    val isHandoffSession by produceState(false, expanded) {
        value = expanded && tokenManager.hasTemporaryScope()
    }
    // From the account's cached list and limit, which the picker's loads and
    // the launch and return refreshes keep current.
    val profileRow = activeAccount.profileMenuRow(isHandoffSession)
    // The username only tells accounts apart when the server has more than one (spec §4.3).
    val username = activeAccount?.username?.takeIf { name ->
        name.isNotBlank() && accounts.count { it.serverId == activeServer?.id } > 1
    }

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
                    listOfNotNull(username, server.displayName, ServerBranding.hostLabel(server.url))
                        .distinct()
                        .joinToString(" · ")
                },
                leading = {
                    ProfileAvatar(avatar = activeProfile.avatarRef(), name = activeProfile.name, size = 40.dp)
                },
            )
            SiloMenuDivider()
        }
        if (onRequestsClick != null) {
            SiloMenuItem(
                label = stringResource(CommonR.string.menu_requests),
                icon = Icons.Rounded.Inbox,
                onClick = {
                    onDismissRequest()
                    onRequestsClick()
                },
            )
        }
        if (onWatchPartyClick != null) {
            SiloMenuItem(
                label = stringResource(CommonR.string.menu_profileMenu_watchParty),
                icon = Icons.Rounded.Groups,
                onClick = {
                    onDismissRequest()
                    onWatchPartyClick()
                },
            )
        }
        SiloMenuItem(
            label = stringResource(CommonR.string.menu_settings),
            icon = Icons.Rounded.Settings,
            showDivider = hasFeatureGroup,
            onClick = {
                onDismissRequest()
                onSettingsClick()
            },
        )
        when (profileRow) {
            ProfileMenuRow.SwitchProfile -> SiloMenuItem(
                label = stringResource(CommonR.string.menu_profileMenu_switchProfile),
                icon = Icons.Rounded.People,
                onClick = {
                    onDismissRequest()
                    onSwitchProfileClick()
                },
            )
            ProfileMenuRow.AddProfile -> SiloMenuItem(
                label = stringResource(CommonR.string.menu_addProfile),
                icon = Icons.Rounded.PersonAdd,
                onClick = {
                    onDismissRequest()
                    addProfile()
                },
            )
            ProfileMenuRow.None -> Unit
        }
        SiloMenuItem(
            label = stringResource(CommonR.string.changeAccount_menu),
            icon = Icons.Rounded.SwitchAccount,
            onClick = {
                onDismissRequest()
                onChangeAccountClick()
            },
        )
        SiloMenuItem(
            label = stringResource(CommonR.string.common_signOut),
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
            onSignOutClick(false)
        },
        onDismiss = { confirmSignOut = false },
        onConfirmAndRemoveServer = {
            confirmSignOut = false
            onSignOutClick(true)
        },
    )
}
