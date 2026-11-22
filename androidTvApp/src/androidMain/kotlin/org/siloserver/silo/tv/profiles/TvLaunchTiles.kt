package org.siloserver.silo.tv.profiles

import org.siloserver.silo.common.profiles.ProfileLaunchPreferences
import org.siloserver.silo.model.server.CachedProfile
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.tv.watchnext.WatchNextSeeder

/**
 * The open account's only profile, when it has no PIN and the account needs
 * no sign-in. The picker never appears for it (owner decision 2026-11-02: the
 * picker shows one account at a time): there is nothing to choose and nothing
 * to protect. With several accounts saved, Profile Selection asks whose
 * account instead of keeping it open (see `profileSelectionPrompt`).
 */
internal fun ServerRegistry.onlyOpenTile(): CachedProfile? {
    val account = activeAccount.value?.takeIf { !it.needsSignIn } ?: return null
    return account.cachedProfiles.singleOrNull()?.takeIf { !it.hasPin }
}

/**
 * No account is open (signed out, or the active one waits to sign in again)
 * while another saved account still has a session: Change Account is the
 * root screen instead of the picker (owner decision 2026-11-02).
 */
internal fun ServerRegistry.noAccountOpenButAnotherSignedIn(): Boolean {
    val active = activeAccount.value
    if (active != null && !active.needsSignIn) return false
    return accounts.value.any { !it.needsSignIn && it.localAccountId != active?.localAccountId }
}

/** [onlyOpenTile] is the profile open right now. */
internal suspend fun ServerRegistry.onlyOpenTileIsActive(tokenManager: TokenManager): Boolean {
    val only = onlyOpenTile() ?: return false
    return only.id == tokenManager.getProfileId()
}

/**
 * Automatic reopens the last tile, whichever server and account it is on
 * (spec §4.2), when no profile is open: someone switched accounts in the
 * picker and left before choosing. The tile opens only where that needs no
 * PIN: the account still holds the profile with its PIN proof, or the profile
 * has no PIN. Otherwise the picker shows. Switching tiles clears Watch Next
 * and seeds it for the new one.
 */
internal suspend fun restoreLastTile(
    registry: ServerRegistry,
    tokenManager: TokenManager,
    launchPreferences: ProfileLaunchPreferences,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    watchNextSeeder: WatchNextSeeder,
) {
    if (!tokenManager.getProfileId().isNullOrBlank()) return
    val tile = launchPreferences.restorableTile(registry.accounts.value) ?: return
    val account = registry.accounts.value.firstOrNull { it.localAccountId == tile.accountId } ?: return
    if (account.needsSignIn) return
    val remembered = account.profileId == tile.profileId
    val cached = account.cachedProfiles.firstOrNull { it.id == tile.profileId }
    if (!remembered && cached?.hasPin != false) return
    val switching = registry.activeServerId.value != tile.serverId || registry.activeAccountId.value != tile.accountId
    if (switching) {
        authRepository.switchToAccount(tile.serverId, tile.accountId)
        if (registry.activeAccountId.value != tile.accountId) return
    }
    if (tokenManager.getProfileId() != tile.profileId) profileRepository.selectProfile(tile.profileId)
    if (switching) {
        watchNextSeeder.clear()
        watchNextSeeder.seedNow()
        watchNextSeeder.enqueuePeriodic()
    }
}
