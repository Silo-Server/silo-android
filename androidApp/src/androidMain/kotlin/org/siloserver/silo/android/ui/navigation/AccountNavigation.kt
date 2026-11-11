package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.model.server.CachedProfile
import org.siloserver.silo.network.ServerRegistry

// Routing rules for several accounts (owner decision 2026-11-02, Roku spec
// §7.5): "Who's watching?" shows the open account's profiles only, and Change
// Account reaches the others. Shared by launch, sign-out and session expiry.
// When Profile Selection asks with several accounts saved, Change Account
// shows in the picker's place.

/** Another saved account, on any server, still has a session to open. */
internal fun ServerRegistry.hasOtherSession(): Boolean {
    val active = activeAccountId.value
    return accounts.value.any { it.localAccountId != active && !it.needsSignIn }
}

/** Change Account has something besides the active account: another account or another server. */
internal fun ServerRegistry.hasOtherAccountsOrServers(): Boolean {
    val active = activeAccountId.value
    return accounts.value.any { it.localAccountId != active } || entries.value.size > 1
}

/**
 * Where to go with no account open (after a sign-out or an expiry): Change
 * Account as the root while another account has a session, sign-in
 * otherwise, server setup when nothing is saved.
 */
internal fun ServerRegistry.routeAfterSignOut(): String = when {
    entries.value.isEmpty() -> Route.ServerSetup.route
    hasOtherSession() -> Route.ChangeAccount().route
    else -> Route.Login.route
}

/**
 * The open account's only profile, when it has no PIN: the picker never shows
 * for it. With one account saved, launch and return rules keep that profile
 * instead of asking. A launch with no profile remembered opens it when the
 * server does not answer; when it does, its current list decides (see
 * `launchOnlyProfileId`). With several saved, Profile Selection asks whose
 * account instead (see `profileSelectionPrompt`).
 */
internal fun ServerRegistry.onlyOpenTile(): CachedProfile? {
    val account = activeAccount.value ?: return null
    if (account.needsSignIn || account.serverId != activeServerId.value) return null
    return account.cachedProfiles.singleOrNull()?.takeIf { !it.hasPin }
}

/** [onlyOpenTile] exists: the open account has one profile and it has no PIN. */
internal fun ServerRegistry.hasSingleOpenTile(): Boolean = onlyOpenTile() != null
