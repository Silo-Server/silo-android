package org.siloserver.silo.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Sign Out's choice, as on Apple TV (silo-apple `TVMainTabView`,
 * `TVSettingsView`, `ProfileSelectionView`): keep the server and go back to
 * its sign-in screen, or forget it too. Cancel comes first and takes focus.
 * Wider than the default card so the third label fits on its row.
 */
@Composable
fun TvSignOutDialog(
    onSignOut: (removeServer: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    TvDialog(
        title = "Sign Out",
        message = "Choose whether to keep or remove this server from this TV.",
        onDismissRequest = onDismiss,
        width = 640.dp,
        actions = listOf(
            TvDialogAction(label = "Cancel", onClick = onDismiss),
            TvDialogAction(
                label = "Sign Out",
                onClick = { onSignOut(false) },
                style = TvDialogActionStyle.Destructive,
            ),
            TvDialogAction(
                label = "Sign Out & Remove Server",
                onClick = { onSignOut(true) },
                style = TvDialogActionStyle.Destructive,
            ),
        ),
    )
}
