package org.siloserver.silo.tv.ui.screens.servers

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.tv.ui.components.TvDialog
import org.siloserver.silo.tv.ui.components.TvDialogAction
import org.siloserver.silo.tv.ui.components.TvDialogActionStyle
import org.siloserver.silo.tv.ui.components.TvDialogOption
import org.siloserver.silo.tv.ui.components.TvOptionDialog
import org.siloserver.silo.tv.ui.components.TvSignOutDialog
import org.siloserver.silo.tv.ui.components.TvTextInputDialog
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.tv.ui.screens.settings.SettingsActionRow
import org.siloserver.silo.tv.ui.screens.settings.SettingsBackground
import org.siloserver.silo.tv.ui.screens.settings.SettingsFooterText
import org.siloserver.silo.tv.ui.screens.settings.SettingsGroupHeaderStyle
import org.siloserver.silo.tv.ui.screens.settings.SettingsGroupRowSpacing
import org.siloserver.silo.tv.ui.screens.settings.SettingsPaneHeader
import org.siloserver.silo.tv.ui.screens.settings.SettingsValueRow
import org.siloserver.silo.tv.ui.theme.Spacing

/**
 * Settings › Accounts on Android TV (multi-account spec §4.5), in place of
 * the old server list, laid out as Roku's Accounts pane:
 *
 * ```
 * [≡] Accounts
 *     Servers and the accounts signed in to them on this TV.
 * HOME
 *   alex                               1 profile  ›
 *   sam                               2 profiles  ›
 *   Add an account on Home                        ›
 *   Rename Server                   10.0.0.5:8413 ›
 *   Remove Server
 * Add a server                                    ›
 * Signing out of an account removes it …
 * ```
 *
 * An account's page names it with its profiles and offers Sign in again and
 * Sign out. Switching between accounts happens in Change Account, not here.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAccountsScreen(
    onAddServer: () -> Unit,
    /** Sign in on the now-active server; a saved account's username fills the form. */
    onSignIn: (username: String?) -> Unit,
    onSwitched: (TvServerSwitchDestination) -> Unit,
    onBack: () -> Unit,
    viewModel: TvAccountsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var openAccount by remember { mutableStateOf<AccountEntry?>(null) }
    var confirmSignOut by remember { mutableStateOf<AccountEntry?>(null) }
    var renameServer by remember { mutableStateOf<ServerEntry?>(null) }
    var confirmRemove by remember { mutableStateOf<ServerEntry?>(null) }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(state.navigation) {
        val navigation = state.navigation ?: return@LaunchedEffect
        viewModel.onNavigationConsumed()
        when (navigation) {
            is TvAccountsNavigation.SignIn -> onSignIn(navigation.username)
            is TvAccountsNavigation.Switched -> onSwitched(navigation.destination)
        }
    }

    val context = LocalContext.current
    LaunchedEffect(state.removeFailed) {
        if (state.removeFailed) {
            viewModel.onRemoveFailedConsumed()
            Toast.makeText(context, TV_REMOVE_SERVER_FAILED, Toast.LENGTH_LONG).show()
        }
    }

    val rows = remember(state.servers) { accountsRows(state.servers) }
    fun serverName(serverId: String): String? =
        state.servers.firstOrNull { it.server.id == serverId }?.server?.displayName
    val firstFocus = remember { FocusRequester() }
    val firstFocusKey = rows.firstOrNull { it is AccountsRow.Account }?.key
        ?: rows.firstOrNull { it is AccountsRow.AddAccount }?.key
        ?: AccountsRow.AddServer.key
    // The rows are lazy, so the first request can land before placement; this
    // retries until focus is actually observed.
    val contentInitialFocus = rememberTvContentInitialFocus(target = firstFocus, contentKey = firstFocusKey)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(contentInitialFocus)
            .background(SettingsBackground)
            .padding(start = 64.dp, top = Spacing.safeArea, end = 44.dp),
    ) {
        SettingsPaneHeader(
            icon = Icons.Outlined.Dns,
            title = "Accounts",
            blurb = "Servers and the accounts signed in to them on this TV.",
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SettingsGroupRowSpacing),
            contentPadding = PaddingValues(bottom = Spacing.xxxl),
        ) {
            items(rows, key = { it.key }) { row ->
                val focus = if (row.key == firstFocusKey) Modifier.focusRequester(firstFocus) else Modifier
                when (row) {
                    is AccountsRow.Server -> ServerSectionLabel(row.server.displayName)
                    is AccountsRow.Account -> SettingsValueRow(
                        label = row.account.displayName(),
                        value = accountValue(row.account, current = row.account.localAccountId == state.activeAccountId),
                        onClick = { openAccount = row.account },
                        modifier = focus,
                    )
                    is AccountsRow.AddAccount -> SettingsActionRow(
                        label = "Add an account on ${row.server.displayName}",
                        onClick = { viewModel.onAddAccount(row.server.id) },
                        modifier = focus,
                    )
                    is AccountsRow.Rename -> SettingsValueRow(
                        label = "Rename Server",
                        value = serverHost(row.server.url),
                        onClick = { renameServer = row.server },
                    )
                    is AccountsRow.Remove -> SettingsActionRow(
                        label = "Remove Server",
                        onClick = { confirmRemove = row.server },
                        destructive = true,
                    )
                    AccountsRow.AddServer -> SettingsActionRow(
                        label = "Add a server",
                        onClick = onAddServer,
                        modifier = focus.padding(top = 10.dp),
                    )
                    AccountsRow.Footer -> SettingsFooterText(
                        text = "Signing out of an account removes it and its settings from this TV. Its server stays saved.",
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }

    // An account's page: who it is, its profiles, Sign in again and Sign out.
    openAccount?.let { account ->
        TvDialog(
            title = account.displayName(),
            message = accountPageMessage(account, serverName(account.serverId).orEmpty()),
            onDismissRequest = { openAccount = null },
            actions = listOf(
                TvDialogAction(
                    label = "Sign in again",
                    onClick = {
                        openAccount = null
                        viewModel.onSignInAgain(account)
                    },
                ),
                TvDialogAction(
                    label = "Sign Out",
                    onClick = {
                        openAccount = null
                        confirmSignOut = account
                    },
                    style = TvDialogActionStyle.Destructive,
                ),
            ),
        )
    }

    confirmSignOut?.let { account ->
        TvSignOutDialog(
            accountName = account.username?.takeIf { it.isNotBlank() } ?: "this account",
            serverName = serverName(account.serverId),
            serverId = account.serverId,
            onSignOut = { removeServer ->
                confirmSignOut = null
                viewModel.onSignOut(account, removeServer)
            },
            onDismiss = { confirmSignOut = null },
        )
    }

    renameServer?.let { server ->
        TvTextInputDialog(
            title = "Rename Server",
            label = "Name",
            confirmLabel = "Rename",
            initialValue = server.displayName,
            // Blank goes back to the name the server gives itself.
            allowBlank = true,
            onConfirm = { name ->
                renameServer = null
                viewModel.onRename(server.id, name)
            },
            onDismiss = { renameServer = null },
        )
    }

    confirmRemove?.let { server ->
        TvOptionDialog(
            title = "Remove ${server.displayName}?",
            options = listOf(
                TvDialogOption(
                    key = "confirm",
                    title = "Remove server",
                    subtitle = "Signs out of every account on this server and removes it from this TV.",
                    onClick = {
                        confirmRemove = null
                        viewModel.onRemoveServer(server.id)
                    },
                ),
                TvDialogOption(
                    key = "cancel",
                    title = "Cancel",
                    onClick = { confirmRemove = null },
                ),
            ),
            onDismiss = { confirmRemove = null },
            initialFocusKey = "cancel",
        )
    }
}

private sealed interface AccountsRow {
    val key: String

    data class Server(val server: ServerEntry) : AccountsRow {
        override val key = "server/${server.id}"
    }

    data class Account(val account: AccountEntry) : AccountsRow {
        override val key = "account/${account.localAccountId}"
    }

    data class AddAccount(val server: ServerEntry) : AccountsRow {
        override val key = "add-account/${server.id}"
    }

    data class Rename(val server: ServerEntry) : AccountsRow {
        override val key = "rename/${server.id}"
    }

    data class Remove(val server: ServerEntry) : AccountsRow {
        override val key = "remove/${server.id}"
    }

    data object AddServer : AccountsRow {
        override val key = "add-server"
    }

    data object Footer : AccountsRow {
        override val key = "footer"
    }
}

private fun accountsRows(servers: List<TvAccountsServer>): List<AccountsRow> = buildList {
    servers.forEach { group ->
        add(AccountsRow.Server(group.server))
        group.accounts.forEach { add(AccountsRow.Account(it)) }
        add(AccountsRow.AddAccount(group.server))
        add(AccountsRow.Rename(group.server))
        add(AccountsRow.Remove(group.server))
    }
    add(AccountsRow.AddServer)
    add(AccountsRow.Footer)
}

/** Management screens say "Account", never "User" (spec §2). */
private fun AccountEntry.displayName(): String = username?.takeIf { it.isNotBlank() } ?: "Account"

private fun profileCount(count: Int): String = if (count == 1) "1 profile" else "$count profiles"

/**
 * "2 profiles", "1 profile", or "Sign in again" (Roku `accountValue`), led by
 * "Current" on the open account so the list shows which one is in use.
 */
private fun accountValue(account: AccountEntry, current: Boolean): String {
    if (account.needsSignIn) return "Sign in again"
    val profiles = account.cachedProfiles.takeIf { it.isNotEmpty() }?.let { profileCount(it.size) }
    return listOfNotNull("Current".takeIf { current }, profiles).joinToString(" · ")
}

/** The address without its scheme, as Rename Server's value shows it. */
private fun serverHost(url: String): String = url.substringAfter("://").trimEnd('/')

private fun accountPageMessage(account: AccountEntry, serverName: String): String = buildString {
    append(serverName)
    val profiles = account.cachedProfiles
    if (profiles.isNotEmpty()) {
        append("\n").append(profileCount(profiles.size)).append(": ")
        append(profiles.joinToString(", ") { it.name })
    }
    if (account.needsSignIn) append("\nThis account needs to sign in again.")
}

/** A server's name above its rows, as a settings group header. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerSectionLabel(name: String) {
    Text(
        text = name.uppercase(),
        style = SettingsGroupHeaderStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
    )
}
