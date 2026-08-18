package org.siloserver.silo.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.siloserver.silo.android.ui.theme.SiloDestructive

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
 * with `requests_enabled` off, and a null [onWatchTogetherClick] is the
 * client-side Watch Together gate; neither is ever shown unconditionally, and
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
    onWatchTogetherClick: (() -> Unit)? = null,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    // Whether anything sits above the account actions.
    //
    // The menu carries exactly one hairline, and this is what decides whether
    // it is drawn at all. A settings card rules every row but its first and
    // separates *groups* by being a different card; a single popup cannot be
    // two cards, so ruling every row here would spend the same line on both
    // jobs and the feature/account split would stop reading as a split. The
    // old menu drew its divider unconditionally, so a server with requests
    // disabled opened onto a stray rule above its first item.
    val hasFeatureGroup = onRequestsClick != null || onWatchTogetherClick != null

    SiloDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        if (onRequestsClick != null) {
            SiloMenuItem(
                label = "Requests",
                onClick = {
                    onDismissRequest()
                    onRequestsClick()
                },
            )
        }
        if (onWatchTogetherClick != null) {
            SiloMenuItem(
                label = "Watch together",
                onClick = {
                    onDismissRequest()
                    onWatchTogetherClick()
                },
            )
        }
        SiloMenuItem(
            label = "Settings",
            showDivider = hasFeatureGroup,
            onClick = {
                onDismissRequest()
                onSettingsClick()
            },
        )
        SiloMenuItem(
            label = "Switch profile",
            onClick = {
                onDismissRequest()
                onSwitchProfileClick()
            },
        )
        SiloMenuItem(
            label = "Switch server",
            onClick = {
                onDismissRequest()
                onSwitchServerClick()
            },
        )
        SiloMenuItem(
            label = "Sign out",
            labelColor = SiloDestructive,
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
