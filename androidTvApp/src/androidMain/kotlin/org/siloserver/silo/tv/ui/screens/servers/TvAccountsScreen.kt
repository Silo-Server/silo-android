package org.siloserver.silo.tv.ui.screens.servers

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.ui.marquee.MarqueeServerMark
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
import org.siloserver.silo.tv.ui.screens.settings.SettingsBackground
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.InterFamily
import org.siloserver.silo.tv.ui.theme.Spacing

/**
 * Settings › Accounts on Android TV (multi-account spec §4.5), in place of
 * the old server list:
 *
 * ```
 * Accounts
 *   Home                              [Rename] [Remove server]
 *     alex       3 profiles                 ›
 *     sam        1 profile                  ›
 *     + Add an account on Home
 *   + Add a server
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(contentInitialFocus)
            .background(SettingsBackground)
            .padding(start = 44.dp, top = Spacing.safeArea, end = 44.dp, bottom = Spacing.xxxl),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(
                modifier = Modifier.width(200.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
                Text(
                    text = "Accounts",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = "The servers and accounts saved on this TV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                )
            }

            LazyColumn(
                modifier = Modifier.widthIn(max = AccountsMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    val focus = if (row.key == firstFocusKey) Modifier.focusRequester(firstFocus) else Modifier
                    when (row) {
                        is AccountsRow.Server -> ServerHeaderRow(
                            server = row.server,
                            onRename = { renameServer = row.server },
                            onRemove = { confirmRemove = row.server },
                        )
                        is AccountsRow.Account -> AccountsActionRow(
                            title = row.account.displayName(),
                            subtitle = accountSummary(row.account),
                            trailingCheck = row.account.localAccountId == state.activeAccountId,
                            trailingIcon = Icons.Default.ChevronRight,
                            onClick = { openAccount = row.account },
                            modifier = focus.padding(start = AccountIndent),
                        )
                        is AccountsRow.AddAccount -> AccountsActionRow(
                            title = "Add an account on ${row.server.displayName}",
                            leadingIcon = Icons.Default.Add,
                            onClick = { viewModel.onAddAccount(row.server.id) },
                            modifier = focus.padding(start = AccountIndent),
                        )
                        AccountsRow.AddServer -> AccountsActionRow(
                            title = "Add a server",
                            leadingIcon = Icons.Default.Add,
                            onClick = onAddServer,
                            modifier = focus.padding(top = 10.dp),
                        )
                    }
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

    data object AddServer : AccountsRow {
        override val key = "add-server"
    }
}

private fun accountsRows(servers: List<TvAccountsServer>): List<AccountsRow> = buildList {
    servers.forEach { group ->
        add(AccountsRow.Server(group.server))
        group.accounts.forEach { add(AccountsRow.Account(it)) }
        add(AccountsRow.AddAccount(group.server))
    }
    add(AccountsRow.AddServer)
}

/** Management screens say "Account", never "User" (spec §2). */
private fun AccountEntry.displayName(): String = username?.takeIf { it.isNotBlank() } ?: "Account"

private fun profileCount(count: Int): String = if (count == 1) "1 profile" else "$count profiles"

private fun accountSummary(account: AccountEntry): String = listOfNotNull(
    account.cachedProfiles.size.takeIf { it > 0 }?.let(::profileCount),
    "Sign in again".takeIf { account.needsSignIn },
).joinToString(" · ")

private fun accountPageMessage(account: AccountEntry, serverName: String): String = buildString {
    append(serverName)
    val profiles = account.cachedProfiles
    if (profiles.isNotEmpty()) {
        append("\n").append(profileCount(profiles.size)).append(": ")
        append(profiles.joinToString(", ") { it.name })
    }
    if (account.needsSignIn) append("\nThis account needs to sign in again.")
}

private val AccountsMaxWidth = 620.dp
private val AccountIndent = 18.dp
private val RowShape = RoundedCornerShape(8.dp)

/** A server's name and address, with Rename and Remove server beside it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerHeaderRow(
    server: ServerEntry,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // The server's branded mark, else the Silo project icon (never its initial).
        MarqueeServerMark(
            name = server.displayName,
            imageUrl = rememberServerMarkUrl(server),
            size = 34.dp,
            siloIconFallback = true,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = server.url,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = InterFamily,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HeaderButton(label = "Rename", onClick = onRename)
        HeaderButton(label = "Remove server", onClick = onRemove, destructive = true)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeaderButton(label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.055f),
            contentColor = if (destructive) MaterialTheme.colorScheme.error else Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = InterFamily,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountsActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    /** The account in use right now. */
    trailingCheck: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val foreground = if (isFocused) FocusedContent else Color.White
    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.055f),
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
        ),
        shape = CardDefaults.shape(shape = RowShape),
        scale = CardDefaults.scale(focusedScale = 1f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.sm))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = InterFamily,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = InterFamily,
                        color = foreground.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailingCheck) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "In use",
                    tint = foreground.copy(alpha = 0.72f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            if (trailingIcon != null) {
                Icon(trailingIcon, contentDescription = null, tint = foreground.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
            }
        }
    }
}
