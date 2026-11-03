package org.siloserver.silo.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.server.CachedProfile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.ProfileApi

/**
 * Keeps each saved account's cached profile list (its offline copy, spec
 * §4.1) current. Change Account renders from the cache straight away and
 * calls [refreshAll] in the background; a server that can't be reached keeps
 * its last list.
 */
class AccountProfilesRefresher(
    private val registry: ServerRegistry,
    private val profileApi: ProfileApi,
    private val tokenManager: TokenManager,
) {
    suspend fun refreshAll() = coroutineScope {
        registry.accounts.value
            .filter { !it.needsSignIn }
            .map { account -> async { refresh(account.localAccountId) } }
            .forEach { it.await() }
    }

    suspend fun refresh(accountId: String) {
        val account = registry.accounts.value.firstOrNull { it.localAccountId == accountId } ?: return
        if (account.needsSignIn) return
        val url = registry.entries.value.firstOrNull { it.id == account.serverId }?.url ?: return
        val generation = runCatching { tokenManager.captureAccountSessionExpectation() }.getOrNull()?.generation ?: return
        val scope = AuthScopeSnapshot.profileless(account.serverId, url, generation, accountId)
        val result = profileApi.listProfiles(scope) as? ApiResult.Success ?: return
        registry.setCachedProfiles(accountId, result.data.profiles.map { it.toCached() })
    }

    /**
     * Record a list the active account just fetched itself (the profile
     * screen's own load). [accountId] is the account active when that load
     * started; when another account is active by now the list is not
     * recorded, so it never lands in the wrong account's cache.
     */
    suspend fun recordActive(accountId: String?, profiles: List<Profile>) {
        if (accountId == null || registry.activeAccountId.value != accountId) return
        registry.setCachedProfiles(accountId, profiles.map { it.toCached() })
    }
}

fun Profile.toCached() = CachedProfile(
    id = id,
    name = name,
    avatar = avatar,
    avatarUrl = avatarUrl,
    isPrimary = isPrimary,
    hasPin = hasPin,
    isChild = isChild,
)
