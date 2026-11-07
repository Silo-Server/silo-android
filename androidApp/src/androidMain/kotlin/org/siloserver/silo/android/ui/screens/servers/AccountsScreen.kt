package org.siloserver.silo.android.ui.screens.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.ui.marquee.MarqueeServerMark
import org.siloserver.silo.common.ui.marquee.ServerBrandingLoader
import org.siloserver.silo.common.ui.marquee.rememberSignInServer
import org.siloserver.silo.android.ui.components.SignOutConfirmDialog
import org.siloserver.silo.android.ui.components.SiloDialog
import org.siloserver.silo.android.ui.components.SiloDialogAction
import org.siloserver.silo.android.ui.components.SiloDialogActionStyle
import org.siloserver.silo.android.ui.components.boldFormat
import org.siloserver.silo.android.ui.components.downloadsSentence
import org.siloserver.silo.android.ui.screens.settings.SettingsButtonRow
import org.siloserver.silo.android.ui.screens.settings.SettingsNavigationRow
import org.siloserver.silo.android.ui.screens.settings.SettingsPageScaffold
import org.siloserver.silo.android.ui.screens.settings.SettingsSection
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionCard
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.common.downloads.DownloadSummary
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.model.server.ServerEntry

/**
 * Settings › Accounts (multi-account spec §4.5), the management surface that
 * replaced the server list: each saved server with Rename and Remove server,
 * its accounts ("alex — 3 profiles · 12 downloads ›"), "Add an account on
 * {server}", and "Add a server". Switching happens in Change account; this
 * screen manages. Wording is "Account", never "User".
 */
@Composable
fun AccountsScreen(
    onAddServer: () -> Unit,
    onOpenAccount: (accountId: String) -> Unit,
    onSwitched: (ServerSwitchDestination) -> Unit,
    onBack: () -> Unit,
    viewModel: ServerListViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var renameTarget by remember { mutableStateOf<ServerEntry?>(null) }
    var removeTarget by remember { mutableStateOf<ServerEntry?>(null) }

    LaunchedEffect(state.switchedTo) {
        val destination = state.switchedTo ?: return@LaunchedEffect
        viewModel.onSwitchConsumed()
        onSwitched(destination)
    }

    SettingsPageScaffold(title = stringResource(CommonR.string.accounts_title), onBackClick = onBack) {
        state.servers.forEach { server ->
            item(key = "server-${server.id}") {
                Column {
                    ServerHeader(
                        server = server,
                        isOnLocalNetwork = server.id == state.onLocalNetworkId,
                        enabled = !state.isWorking,
                        onRename = { renameTarget = server },
                        onRemove = { removeTarget = server },
                    )
                    SettingsSectionCard {
                        state.accountsOn(server.id).forEach { account ->
                            SettingsNavigationRow(
                                label = account.displayName(),
                                description = stringResource(CommonR.string.account_signInAgain).takeIf { account.needsSignIn },
                                value = accountSummary(account, state.downloads[account.localAccountId]),
                                onClick = { onOpenAccount(account.localAccountId) },
                                enabled = !state.isWorking,
                            )
                        }
                        SettingsNavigationRow(
                            label = stringResource(CommonR.string.accounts_addAccountOnServer, server.displayName),
                            icon = Icons.Filled.Add,
                            onClick = { viewModel.onAddAccount(server.id) },
                            showChevron = false,
                            enabled = !state.isWorking,
                        )
                    }
                }
            }
        }
        item(key = "add-server") {
            SettingsSectionCard {
                SettingsNavigationRow(
                    label = stringResource(CommonR.string.accounts_addAServer),
                    icon = Icons.Filled.Add,
                    onClick = onAddServer,
                    showChevron = false,
                    enabled = !state.isWorking,
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            entry = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.onRename(target.id, newName)
                renameTarget = null
            },
        )
    }

    removeTarget?.let { target ->
        val accounts = state.accountsOn(target.id)
        val total = accounts.mapNotNull { state.downloads[it.localAccountId] }
            .fold(DownloadSummary(0, 0L)) { sum, next -> DownloadSummary(sum.count + next.count, sum.totalBytes + next.totalBytes) }
        val downloads = downloadsSentence(CommonR.string.accounts_remove_downloads, total)
        val body = boldFormat(stringResource(CommonR.string.accounts_remove_body), target.displayName)
        SiloDialog(
            title = stringResource(CommonR.string.accounts_remove_title, target.displayName),
            onDismissRequest = { removeTarget = null },
            actions = listOf(
                SiloDialogAction(
                    stringResource(CommonR.string.accounts_remove_server),
                    {
                        viewModel.onRemove(target.id)
                        removeTarget = null
                    },
                    SiloDialogActionStyle.Destructive,
                ),
                SiloDialogAction(stringResource(CommonR.string.common_cancel), { removeTarget = null }),
            ),
        ) {
            Text(if (downloads == null) body else body + AnnotatedString(" $downloads"))
        }
    }

    state.removeFailedServerId?.let { failedId ->
        val name = state.servers.firstOrNull { it.id == failedId }?.displayName.orEmpty()
        SiloDialog(
            title = stringResource(CommonR.string.accounts_remove_failedTitle, name),
            onDismissRequest = viewModel::onRemoveFailureConsumed,
            actions = listOf(SiloDialogAction(stringResource(CommonR.string.common_done), viewModel::onRemoveFailureConsumed)),
        ) {
            Text(stringResource(CommonR.string.accounts_remove_failedBody))
        }
    }
}

/**
 * One account's page (spec §4.5): its username, server, profiles and
 * downloads, with Sign in again while its sign-in has expired, and Sign out.
 */
@Composable
fun AccountDetailScreen(
    accountId: String,
    onSwitched: (ServerSwitchDestination) -> Unit,
    onBack: () -> Unit,
    viewModel: ServerListViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val account = state.accounts.firstOrNull { it.localAccountId == accountId }
    var confirmSignOut by remember { mutableStateOf(false) }

    // Signing out of the active account leaves this screen for the picker or
    // sign-in; that navigation wins over the pop below.
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(state.switchedTo) {
        val destination = state.switchedTo ?: return@LaunchedEffect
        leaving = true
        viewModel.onSwitchConsumed()
        onSwitched(destination)
    }
    // Signed out of another account here (or the account went elsewhere):
    // nothing left to show. Waits for a running sign-out to say where to go.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(account == null, state.accounts.isEmpty(), state.isWorking) {
        if (account != null) seen = true
        if (account == null && !state.isWorking && !leaving && (seen || state.accounts.isNotEmpty())) onBack()
    }
    if (account == null) return
    val server = state.servers.firstOrNull { it.id == account.serverId }
    val summary = state.downloads[account.localAccountId]

    SettingsPageScaffold(title = account.displayName(), onBackClick = onBack) {
        item(key = "account") {
            SettingsSection(title = null) {
                SettingsNavigationRow(label = stringResource(CommonR.string.account_username), value = account.username ?: "—")
                SettingsNavigationRow(label = stringResource(CommonR.string.account_server), value = server?.displayName)
            }
        }
        if (account.cachedProfiles.isNotEmpty()) {
            item(key = "profiles") {
                SettingsSection(title = stringResource(CommonR.string.account_profiles)) {
                    account.cachedProfiles.forEach { profile -> SettingsNavigationRow(label = profile.name) }
                }
            }
        }
        item(key = "downloads") {
            SettingsSection(title = null) {
                SettingsNavigationRow(
                    label = stringResource(CommonR.string.account_downloads),
                    value = if (summary == null || summary.count <= 0) {
                        stringResource(CommonR.string.account_downloads_none)
                    } else {
                        stringResource(
                            CommonR.string.account_downloads_value,
                            pluralStringResource(CommonR.plurals.account_downloads_count, summary.count, summary.count),
                            formatBytes(summary.totalBytes),
                        )
                    },
                )
            }
        }
        if (account.needsSignIn) {
            item(key = "sign-in-again") {
                SettingsSection(title = null, footer = stringResource(CommonR.string.account_needsSignInFooter)) {
                    SettingsNavigationRow(
                        label = stringResource(CommonR.string.account_signInAgain),
                        onClick = { viewModel.onSignInAgain(account.localAccountId) },
                        enabled = !state.isWorking,
                    )
                }
            }
        }
        item(key = "sign-out") {
            SettingsSectionCard {
                SettingsButtonRow(
                    label = stringResource(CommonR.string.common_signOut),
                    onClick = { confirmSignOut = true },
                    enabled = !state.isWorking,
                )
            }
        }
    }

    SignOutConfirmDialog(
        visible = confirmSignOut,
        serverId = account.serverId,
        accountId = account.localAccountId,
        onConfirm = {
            confirmSignOut = false
            viewModel.onSignOutAccount(account.localAccountId)
        },
        onDismiss = { confirmSignOut = false },
    )
}

@Composable
private fun AccountEntry.displayName(): String =
    username?.takeIf { it.isNotBlank() } ?: stringResource(CommonR.string.settings_accounts_account)

/** "3 profiles · 12 downloads", leaving out what isn't known or is none. */
@Composable
private fun accountSummary(account: AccountEntry, downloads: DownloadSummary?): String? {
    val parts = listOfNotNull(
        account.cachedProfiles.size.takeIf { it > 0 }?.let {
            pluralStringResource(CommonR.plurals.account_profiles_count, it, it)
        },
        downloads?.count?.takeIf { it > 0 }?.let {
            pluralStringResource(CommonR.plurals.account_downloads_count, it, it)
        },
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun ServerHeader(
    server: ServerEntry,
    isOnLocalNetwork: Boolean,
    enabled: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    brandingLoader: ServerBrandingLoader = koinInject(),
) {
    val branding = rememberSignInServer(
        serverUrl = server.url,
        savedName = server.fetchedName,
        loader = brandingLoader,
        tintsBackdrop = false,
    )
    val identity: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The server's mark, or the Silo icon when it has none.
            MarqueeServerMark(
                name = server.displayName,
                imageUrl = branding.branding?.markUrl,
                size = 32.dp,
                siloIconFallback = true,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { heading() },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = server.displayName,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isOnLocalNetwork) {
                        stringResourceWithNames(CommonR.string.accounts_hostOnLocalNetwork, ServerBranding.hostLabel(server.url))
                    } else {
                        ServerBranding.hostLabel(server.url)
                    },
                    fontSize = 12.sp,
                    color = SiloSecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRename, enabled = enabled) {
                Text(stringResource(CommonR.string.accounts_rename), color = SiloOnSurface)
            }
            TextButton(onClick = onRemove, enabled = enabled) {
                Text(stringResource(CommonR.string.accounts_remove_server), color = SiloDestructive)
            }
        }
    }
    // The actions sit beside the name, and move under it when longer labels
    // would leave the name too narrow to read.
    Layout(
        contents = listOf(identity, actions),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 4.dp),
    ) { (identityNodes, actionNodes), constraints ->
        val width = constraints.maxWidth
        val identityNode = identityNodes.first()
        val actionsPlaceable = actionNodes.first().measure(Constraints(maxWidth = width))
        // A short name that fits beside the actions never moves them.
        val identityNeeds = minOf(
            identityNode.maxIntrinsicWidth(constraints.maxHeight),
            ServerHeaderMinIdentityWidth.roundToPx(),
        )
        val sideBySide = width - actionsPlaceable.width >= identityNeeds
        val identityWidth = if (sideBySide) width - actionsPlaceable.width else width
        val identityPlaceable = identityNode.measure(Constraints.fixedWidth(identityWidth))
        if (sideBySide) {
            val height = maxOf(identityPlaceable.height, actionsPlaceable.height)
            layout(width, height) {
                identityPlaceable.placeRelative(0, (height - identityPlaceable.height) / 2)
                actionsPlaceable.placeRelative(identityWidth, (height - actionsPlaceable.height) / 2)
            }
        } else {
            layout(width, identityPlaceable.height + actionsPlaceable.height) {
                identityPlaceable.placeRelative(0, 0)
                actionsPlaceable.placeRelative(width - actionsPlaceable.width, identityPlaceable.height)
            }
        }
    }
}

/**
 * The mark (32dp, 12dp gap) plus the narrowest truncated name worth showing
 * beside the actions; a name shorter than that only needs its own width.
 * English leaves the name about 90dp on a 360dp phone and keeps one row;
 * Spanish leaves about 40dp.
 */
private val ServerHeaderMinIdentityWidth = 44.dp + 64.dp

@Composable
private fun RenameDialog(
    entry: ServerEntry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf(entry.userOverrideName ?: entry.fetchedName.orEmpty()) }
    val hasOverride = entry.userOverrideName != null
    val saveLabel = stringResource(CommonR.string.common_save)
    val resetLabel = stringResource(CommonR.string.servers_rename_reset)
    val cancelLabel = stringResource(CommonR.string.common_cancel)
    SiloDialog(
        title = stringResource(CommonR.string.servers_rename_title),
        message = stringResource(CommonR.string.servers_rename_message),
        onDismissRequest = onDismiss,
        actions = buildList {
            add(SiloDialogAction(saveLabel, { onConfirm(input) }, SiloDialogActionStyle.Primary))
            if (hasOverride) {
                add(SiloDialogAction(resetLabel, { onConfirm("") }))
            }
            add(SiloDialogAction(cancelLabel, onDismiss))
        },
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(CommonR.string.servers_rename_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
