package org.siloserver.silo.android.ui.components

import org.siloserver.silo.common.i18n.stringResourceWithNames
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.downloads.DownloadMetadataStore
import org.siloserver.silo.common.downloads.DownloadSummary
import org.siloserver.silo.network.ServerRegistry

/**
 * [template] (a string resource with `%1$s`-style arguments) with each
 * argument in bold, for confirmations that name an account and a server.
 */
fun boldFormat(template: String, vararg args: String): AnnotatedString = buildAnnotatedString {
    val placeholder = Regex("%(\\d+)\\\$s")
    var last = 0
    for (match in placeholder.findAll(template)) {
        append(template.substring(last, match.range.first))
        val arg = args.getOrNull(match.groupValues[1].toInt() - 1).orEmpty()
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(arg) }
        last = match.range.last + 1
    }
    append(template.substring(last))
}

/** [accountId]'s downloads on [serverId], read once; null until read. */
@Composable
fun rememberDownloadSummary(serverId: String?, accountId: String?): DownloadSummary? {
    val store: DownloadMetadataStore = koinInject()
    val summary by produceState<DownloadSummary?>(initialValue = null, serverId, accountId) {
        value = if (serverId == null || accountId == null) null
        else runCatching { store.downloadSummary(serverId, accountId) }.getOrNull()
    }
    return summary
}

/** "12 downloads (8.4 GB)" for [template]'s two arguments, or null without downloads. */
@Composable
fun downloadsSentence(template: Int, summary: DownloadSummary?): String? {
    if (summary == null || summary.count <= 0) return null
    val count = pluralStringResource(CommonR.plurals.account_downloads_count, summary.count, summary.count)
    return stringResource(template, count, formatBytes(summary.totalBytes))
}

/**
 * The sign-out gate shared by the profile menu, the picker, Settings and an
 * account's page. Signing out of an account deletes its downloads (spec
 * §4.4), so the confirmation names the account and server and says how many
 * downloads go: "Sign out of alex on Home?" over "This deletes 12 downloads
 * (8.4 GB) from this device." Cancel is the default: it is drawn as the safe
 * choice and takes focus for a keyboard or D-pad.
 *
 * Without [serverId] and [accountId] it confirms the active account.
 * [onConfirmAndRemoveServer] adds "Sign out & remove server", offered only
 * while that server has no other account.
 */
@Composable
fun SignOutConfirmDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    serverId: String? = null,
    accountId: String? = null,
    onConfirmAndRemoveServer: (() -> Unit)? = null,
) {
    if (!visible) return
    val registry: ServerRegistry = koinInject()
    val activeServerId by registry.activeServerId.collectAsState()
    val activeAccountId by registry.activeAccountId.collectAsState()
    val entries by registry.entries.collectAsState()
    val accounts by registry.accounts.collectAsState()
    val targetServer = serverId ?: activeServerId
    val targetAccount = accountId ?: activeAccountId
    val server = entries.firstOrNull { it.id == targetServer }
    val account = accounts.firstOrNull { it.localAccountId == targetAccount }
    val summary = rememberDownloadSummary(targetServer, targetAccount)
    val accountName = account?.username?.takeIf { it.isNotBlank() } ?: stringResource(CommonR.string.account_fallbackName)
    val serverName = server?.displayName.orEmpty()
    val downloads = downloadsSentence(CommonR.string.account_signOut_downloads, summary)
    val serverHasOtherAccount = accounts.any { it.serverId == targetServer && it.localAccountId != targetAccount }
    SiloDialog(
        title = stringResourceWithNames(CommonR.string.account_signOut_question, accountName, serverName),
        message = downloads,
        onDismissRequest = onDismiss,
        icon = Icons.AutoMirrored.Rounded.Logout,
        iconTint = SiloDestructive,
        actions = buildList {
            add(SiloDialogAction(stringResource(CommonR.string.common_signOut), onConfirm, SiloDialogActionStyle.DestructiveQuiet))
            if (onConfirmAndRemoveServer != null && server != null && !serverHasOtherAccount) {
                add(
                    SiloDialogAction(
                        stringResource(CommonR.string.account_signOut_removeServer),
                        onConfirmAndRemoveServer,
                        SiloDialogActionStyle.DestructiveQuiet,
                    ),
                )
            }
            add(
                SiloDialogAction(
                    stringResource(CommonR.string.common_cancel),
                    onDismiss,
                    SiloDialogActionStyle.Primary,
                    focusedByDefault = true,
                ),
            )
        },
    )
}
