package org.siloserver.silo.repository

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.network.TokenManager

/**
 * Catches an access change made while the app was in the background.
 *
 * The events sockets are the only source of [AccessChangeSignals.reportAccessChanged],
 * and they close when the app stops. The server compares each connection with
 * the policy its own ticket was minted under, so a connection opened after the
 * change never reports it. When the app returns to the foreground, this check
 * fetches the library list and reports an access change when the set of
 * libraries differs from the cached list the screens were built from.
 *
 * It also re-reads the server's Requests capability, so turning Requests off
 * while the app was in the background hides its entry points on return.
 *
 * It sees only changes to which libraries are visible and whether Requests is
 * available. Other policy changes (maturity limits, playback quality) still
 * surface when the affected screen next loads.
 */
class ForegroundAccessCheck(
    private val personalDataRepository: PersonalDataRepository,
    private val accessChanges: AccessChangeSignals,
    private val requestsFeatureStore: RequestsFeatureStore,
    private val tokenManager: TokenManager,
) {
    suspend fun afterReturnToForeground() {
        coroutineScope {
            launch {
                if (personalDataRepository.libraryListChangedSinceCached()) accessChanges.reportAccessChanged()
            }
            launch {
                // The TV shell does not reset the store on sign-out, so check
                // there is still a session before asking the server.
                if (!tokenManager.getAccessToken().isNullOrBlank()) {
                    requestsFeatureStore.refreshAfterReturnToForeground()
                }
            }
        }
    }
}
