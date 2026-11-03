package org.siloserver.silo.android.push

import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.android.auth.ActiveProfileReset
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileCommitResult
import org.siloserver.silo.repository.ProfileRepository

/**
 * Opening a notification makes its tile (server, account, profile) the active
 * one, so its route opens where it was generated rather than being dropped
 * for belonging to someone else.
 *
 * Follows the server list's switch: [AuthRepository.switchToAccount], then
 * the deepest screen that account's stored credentials can reach. A profile
 * that needs no PIN is selected directly; a protected one, or one this device
 * has not seen, goes through Who's Watching, where the route waits until the
 * notification's profile is chosen (or is dropped when another one is).
 */
class NotificationTileSwitch(
    private val authRepository: AuthRepository,
    private val registry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val profileRepository: ProfileRepository,
    private val activeProfileReset: ActiveProfileReset,
    private val playerSettingsStore: PlayerSettingsStore,
) {
    /**
     * Switches to [serverId]'s [accountId] acting as [profileId]. Returns the
     * route to show after the switch, or null when nothing changed: the tile
     * is already active, or it no longer exists (signed out of, or removed).
     */
    suspend fun switchTo(serverId: String, accountId: String, profileId: String): String? {
        val account = registry.accounts.value.firstOrNull { it.localAccountId == accountId && it.serverId == serverId }
            ?: return null
        if (registry.entries.value.none { it.id == serverId }) return null
        val live = tokenManager.snapshotCurrentScope()
        val sameAccount = live?.serverId == serverId && live.accountId == accountId
        if (sameAccount && live?.profileId == profileId) return null

        // Settings still waiting to sync go to the profile they were made on.
        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { playerSettingsStore.flushPendingDeviceSettings() }
        if (!sameAccount) authRepository.switchToAccount(serverId, accountId)
        if (registry.activeServerId.value != serverId || registry.activeAccountId.value != accountId) return null
        if (tokenManager.getAccessToken().isNullOrBlank()) return Route.Login.route

        if (tokenManager.getProfileId() != profileId) {
            val cached = account.cachedProfiles.firstOrNull { it.id == profileId }
            if (cached == null || cached.hasPin) {
                activeProfileReset.clearActiveProfile()
                return Route.ProfileSelection.route
            }
            val scope = profileRepository.captureIdentityScope()
            if (profileRepository.selectProfile(profileId, expectedScope = scope) != ProfileCommitResult.Committed) {
                return Route.ProfileSelection.route
            }
        } else if (!sameAccount && activeProfileReset.clearIfSelectionRequired()) {
            // Who's Watching covers an account switch as it does a server switch.
            return Route.ProfileSelection.route
        }
        // Through the tour gate, as the server list's switch goes.
        return Route.OnboardingTour.route
    }

    private companion object {
        const val FLUSH_TIMEOUT_MS = 5_000L
    }
}
