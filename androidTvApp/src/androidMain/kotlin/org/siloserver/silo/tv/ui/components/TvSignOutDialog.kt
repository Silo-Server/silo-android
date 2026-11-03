package org.siloserver.silo.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import org.koin.core.context.GlobalContext
import org.siloserver.silo.network.ServerRegistry

/**
 * Sign Out's confirmation (multi-account spec §4.4, Roku spec §7.5): the
 * app's own dialog, naming the account and the server, "Sign out of alex on
 * Home?". Signing out leaves the other accounts and the server itself. Sign
 * Out & Remove Server is offered only while the server has no other account.
 * Android TV keeps no downloads, so there is no download count to state;
 * the message says what leaves and what stays, as Roku's does.
 *
 * Cancel comes first and takes focus: a dialog that opened on Sign Out
 * signed someone out by accident on a real TV.
 *
 * [accountName], [serverName] and [serverId] default to the active account
 * and server.
 */
@Composable
fun TvSignOutDialog(
    onSignOut: (removeServer: Boolean) -> Unit,
    onDismiss: () -> Unit,
    accountName: String? = null,
    serverName: String? = null,
    serverId: String? = null,
) {
    val registry = remember { GlobalContext.getOrNull()?.getOrNull<ServerRegistry>() }
    val activeAccount = registry?.activeAccount?.collectAsState()?.value
    val activeEntry = registry?.activeEntry?.collectAsState()?.value
    val accounts = registry?.accounts?.collectAsState()?.value.orEmpty()
    val account = accountName ?: activeAccount?.username?.takeIf { it.isNotBlank() }
    val server = serverName ?: activeEntry?.displayName
    val targetServerId = serverId ?: activeEntry?.id
    val offerRemoveServer = targetServerId != null && accounts.count { it.serverId == targetServerId } <= 1
    TvDialog(
        title = tvSignOutTitle(account, server),
        message = TV_SIGN_OUT_MESSAGE,
        onDismissRequest = onDismiss,
        // Wide enough for the third label to sit on the same row.
        width = if (offerRemoveServer) 640.dp else TvDialogDefaults.Width,
        actions = listOfNotNull(
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
            ).takeIf { offerRemoveServer },
        ),
        initialFocusIndex = 0,
    )
}

private const val TV_SIGN_OUT_MESSAGE =
    "This account's profiles and settings leave this TV. The server stays saved, so you can sign in again."

/** "Sign out of alex on Home?", dropping whichever part is unknown. */
internal fun tvSignOutTitle(account: String?, server: String?): String = when {
    account != null && server != null -> "Sign out of $account on $server?"
    account != null -> "Sign out of $account?"
    server != null -> "Sign out of $server?"
    else -> "Sign Out?"
}
