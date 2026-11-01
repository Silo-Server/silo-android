package org.siloserver.silo.tv.ui.screens.servers

import kotlinx.coroutines.CancellationException
import org.siloserver.silo.common.profiles.ProfileLaunchPreferences
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.requiresApproval
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.tv.profiles.TvActiveProfileReset

/** Apple's wording (silo-apple `AppRouter.requestSignOut`). */
internal const val TV_REMOVE_SERVER_FAILED = "Silo couldn't remove the saved server. Please try again."

enum class TvServerSwitchDestination { Home, ProfileSelection, Login, ServerSetup }

/**
 * Where the TV goes once the active server changed: picked in Manage Servers,
 * or promoted after the active one was removed there or by Sign Out & Remove
 * Server. One answer for every path, so they land alike.
 */
class TvServerResolver(
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository,
    private val profileLaunchPreferences: ProfileLaunchPreferences,
    private val cleartextConsent: CleartextOriginConsent,
    /** [TvActiveProfileReset.clearActiveProfile]. */
    private val clearActiveProfile: suspend () -> Unit,
) {
    sealed interface Removal {
        /** The entry is still saved: its local write failed. */
        data object Failed : Removal

        /** [destination] is null when the removed server wasn't the active one. */
        data class Removed(val destination: TvServerSwitchDestination?) : Removal
    }

    /**
     * Removes [serverId]. When it was the active server, the registry has
     * promoted the most recently used one (or none) and wiped the removed
     * server's tokens; the screens behind still show the removed server, so
     * the caller must navigate to the returned destination.
     */
    suspend fun remove(serverId: String): Removal {
        val wasActive = serverRegistry.activeServerId.value == serverId
        try {
            serverRegistry.remove(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Removal.Failed
        }
        if (!wasActive) return Removal.Removed(null)
        val promotedId = serverRegistry.activeServerId.value
            ?: return Removal.Removed(TvServerSwitchDestination.ServerSetup)
        // The registry already points at the promoted server, but only the
        // full switch path also moves the token scope and probes the
        // promoted server's identity and v2 contract verdict (it is
        // idempotent when the registry is already there).
        authRepository.switchToServer(promotedId)
        return Removal.Removed(activeDestination())
    }

    /**
     * The deepest screen the now-active server's stored credentials reach.
     *
     * A plain-HTTP server this TV no longer approves goes back to setup, as at
     * launch, which asks again before any credentials go to it.
     *
     * Profile Selection covers a server switch too (silo-apple design §7.5):
     * when it would ask at launch (Every Time, or a timed choice that has run
     * out), the server's saved profile is cleared and Who's Watching shows
     * instead. Otherwise switching servers would reopen a protected profile
     * there without its PIN. Clearing before navigating keeps the picker from
     * loading under an identity that is about to change.
     */
    suspend fun activeDestination(): TvServerSwitchDestination {
        val activeEntry = serverRegistry.activeEntry.value
            ?: return TvServerSwitchDestination.ServerSetup
        if (cleartextConsent.requiresApproval(activeEntry.url)) return TvServerSwitchDestination.ServerSetup
        val accessToken = tokenManager.getAccessToken()
        val profileId = activeEntry.profileId ?: tokenManager.getProfileId()
        return when {
            accessToken.isNullOrBlank() -> TvServerSwitchDestination.Login
            profileId.isNullOrBlank() -> TvServerSwitchDestination.ProfileSelection
            profileLaunchPreferences.requiresSelectionAtLaunch() -> {
                clearActiveProfile()
                TvServerSwitchDestination.ProfileSelection
            }
            else -> TvServerSwitchDestination.Home
        }
    }
}
